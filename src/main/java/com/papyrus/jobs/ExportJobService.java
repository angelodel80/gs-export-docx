package com.papyrus.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.papyrus.api.ExportException;
import com.papyrus.application.DocumentProcessorService;
import com.papyrus.config.*;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import static com.papyrus.jobs.JobSnapshot.State.*;

/** Single-instance bounded executor with disk-spooled input/output and atomic job manifests. */
@Service
public class ExportJobService {
    private static final Logger log = LoggerFactory.getLogger(ExportJobService.class);
    private final AsyncExportProperties config;
    private final ExportProperties limits;
    private final DocumentProcessorService processor;
    private final ObjectMapper json;
    private final Path directory;
    private final Map<String, DeliveryStrategy> deliveries = new LinkedHashMap<>();
    private final ConcurrentMap<UUID, JobSnapshot> jobs = new ConcurrentHashMap<>();
    private final Set<UUID> staging = new HashSet<>();
    private final ThreadPoolExecutor executor;
    private final FileChannel lockChannel;
    private final FileLock lock;

    public ExportJobService(AsyncExportProperties config, ExportProperties limits, DocumentProcessorService processor,
            ObjectMapper json, List<DeliveryStrategy> strategies) throws IOException {
        this.config = config; this.limits = limits; this.processor = processor; this.json = json;
        directory = config.directory().toAbsolutePath().normalize(); Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve(".instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (RuntimeException ex) { lockChannel.close(); throw ex; }
        lock = acquired;
        if (lock == null) { lockChannel.close(); throw new IOException("Job directory already used by another instance"); }
        try {
            for (var strategy : strategies) if (deliveries.putIfAbsent(strategy.name(), strategy) != null)
                throw new IllegalStateException("Duplicate delivery strategy: " + strategy.name());
            recover();
        } catch (IOException | RuntimeException ex) { lock.release(); lockChannel.close(); throw ex; }
        executor = new ThreadPoolExecutor(config.workers(), config.workers(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity()), runnable -> {
                    var thread = new Thread(runnable, "docx-export-worker"); thread.setDaemon(false); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public Map<String, Object> capabilities() {
        return Map.of("maxUploadBytes", config.maxUploadBytes(), "deliveries", deliveries.values().stream()
                .filter(DeliveryStrategy::available).map(DeliveryStrategy::name).toList(), "retentionSeconds", config.retention().toSeconds());
    }

    public JobSnapshot submit(MultipartFile file, String deliveryName) throws IOException {
        var delivery = deliveries.get(deliveryName);
        if (delivery == null || !delivery.available()) throw new ExportException(HttpStatus.BAD_REQUEST, "DELIVERY_UNAVAILABLE", "Modalità di consegna non disponibile.");
        if (file.isEmpty() || file.getSize() > config.maxUploadBytes()) throw ExportException.tooLarge("ZIP vuoto o oltre il limite asincrono.");
        UUID id = UUID.randomUUID();
        synchronized (this) {
            cleanup();
            long reservation = Math.addExact(file.getSize(), config.maxOutputBytes());
            if (jobs.size() + staging.size() >= config.maxStoredJobs()
                    || activeCount() + staging.size() >= config.workers() + config.queueCapacity()) throw busy();
            // Reserve a full maximum upload/output for uploads still being spooled.
            if (reservedBytes() + staging.size() * (config.maxUploadBytes() + config.maxOutputBytes()) + reservation > config.maxDiskBytes())
                throw new ExportException(HttpStatus.SERVICE_UNAVAILABLE, "JOB_STORAGE_FULL", "Spazio riservabile esaurito: riprova dopo la scadenza dei risultati.");
            staging.add(id);
        }
        Path folder = folder(id);
        boolean created = false;
        try {
            Files.createDirectory(folder);
            created = true;
            Files.writeString(folder.resolve(".job-owner"), id.toString(), StandardOpenOption.CREATE_NEW);
            try (var input = file.getInputStream(); var output = Files.newOutputStream(folder.resolve("input.zip"), StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[65536]; long bytes = 0; int n;
                while ((n = input.read(buffer)) != -1) {
                    bytes += n;
                    if (bytes > file.getSize() || bytes > config.maxUploadBytes()) throw ExportException.tooLarge("Upload oltre il limite asincrono.");
                    output.write(buffer, 0, n);
                }
            }
            try (var input = Files.newInputStream(folder.resolve("input.zip"))) {
                if (!Arrays.equals(input.readNBytes(4), new byte[]{'P','K',3,4}))
                    throw new ExportException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INVALID_ZIP", "È richiesto uno ZIP contenente XML.");
            }
            var accepted = new JobSnapshot(id, ACCEPTED, deliveryName, Instant.now(), null, null, null,
                    null, null, null, null, null, null, null, null);
            synchronized (this) { save(accepted); jobs.put(id, accepted); staging.remove(id); }
            try { executor.execute(() -> transform(id, delivery)); }
            catch (RejectedExecutionException ex) {
                synchronized (this) { jobs.remove(id); deleteFolder(id); }
                throw busy();
            }
            return accepted;
        } catch (IOException | RuntimeException ex) {
            synchronized (this) { staging.remove(id); jobs.remove(id); if (created) deleteFolder(id); }
            throw ex;
        }
    }

    public synchronized JobSnapshot status(UUID id) {
        var job = jobs.get(id);
        if (job == null) throw new ExportException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Esportazione inesistente o scaduta.");
        if (job.terminal() && job.expiresAt().isBefore(Instant.now())) {
            if (deleteFolder(id)) jobs.remove(id);
            throw new ExportException(HttpStatus.GONE, "JOB_EXPIRED", "Il risultato è scaduto: invia di nuovo il pacchetto.");
        }
        return job;
    }

    /** Open under the cleanup lock; streaming holds the descriptor rather than the whole DOCX. */
    public synchronized InputStream openDocument(UUID id) throws IOException {
        var job = status(id);
        if (job.state() != READY) throw new ExportException(HttpStatus.CONFLICT, "DOCUMENT_NOT_READY", "Risorsa non ancora pronta.");
        return Files.newInputStream(folder(id).resolve("document.docx"));
    }

    private void transform(UUID id, DeliveryStrategy delivery) {
        var initial = jobs.get(id);
        try {
            update(new JobSnapshot(id, TRANSFORMING, initial.requestedDelivery(), initial.acceptedAt(), Instant.now(),
                    null, null, null, null, null, null, null, null, null, null));
            DocumentProcessorService.FileResult result;
            try (var input = Files.newInputStream(folder(id).resolve("input.zip"))) {
                result = processor.convertToFile(input, folder(id).resolve("document.part"), limits, config);
            }
            Files.move(folder(id).resolve("document.part"), folder(id).resolve("document.docx"), StandardCopyOption.ATOMIC_MOVE);
            var delivered = delivery.deliver(id, folder(id).resolve("document.docx"), result.filename());
            var current = jobs.get(id); Instant now = Instant.now();
            update(new JobSnapshot(id, READY, current.requestedDelivery(), current.acceptedAt(), current.transformationStartedAt(), now,
                    now.plus(config.retention()), result.filename(), result.mediaType(), result.fragments(), delivered.mode(), delivered.url(), delivered.warning(), null, null));
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            String code = ex instanceof ExportException e ? e.code() : "EXPORT_FAILED";
            String message = ex instanceof ExportException ? ex.getMessage() : "Trasformazione non completata. Identificativo: " + id;
            log.warn("Async export {} failed ({})", id, ex.getClass().getSimpleName());
            fail(id, code, message);
            remove(folder(id).resolve("document.part")); remove(folder(id).resolve("document.docx"));
        } finally { remove(folder(id).resolve("input.zip")); }
    }

    private synchronized void update(JobSnapshot job) throws IOException { save(job); jobs.put(job.id(), job); }
    private synchronized void fail(UUID id, String code, String message) {
        var current = jobs.get(id); Instant now = Instant.now();
        var failed = new JobSnapshot(id, FAILED, current.requestedDelivery(), current.acceptedAt(), current.transformationStartedAt(),
                now, now.plus(config.retention()), null, null, null, null, null, null, code, message);
        jobs.put(id, failed);
        try { save(failed); } catch (IOException ex) { log.error("Cannot persist failed job {}", id); }
    }
    private void save(JobSnapshot job) throws IOException {
        Path temp = folder(job.id()).resolve("job.json.tmp"); json.writeValue(temp.toFile(), job);
        Files.move(temp, folder(job.id()).resolve("job.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private void recover() throws IOException {
        try (var children = Files.list(directory)) {
            for (Path path : children.filter(Files::isDirectory).toList()) {
                UUID id;
                try { id = UUID.fromString(path.getFileName().toString()); } catch (IllegalArgumentException ex) { continue; }
                Path manifest = path.resolve("job.json");
                if (!Files.exists(manifest)) {
                    Path owner = path.resolve(".job-owner");
                    if (Files.isRegularFile(owner) && Files.readString(owner).equals(id.toString())) deleteFolder(id);
                    else log.warn("Unrecognized directory left untouched in job storage: {}", id);
                    continue;
                }
                var job = json.readValue(manifest.toFile(), JobSnapshot.class);
                if (!job.id().equals(id)) throw new IOException("Invalid job manifest identity");
                jobs.put(id, job);
                if (!job.terminal()) fail(id, "SERVER_RESTARTED", "Il servizio è stato riavviato durante l'elaborazione: invia nuovamente il pacchetto.");
                else if (job.state() == READY && !Files.isRegularFile(path.resolve("document.docx"))) fail(id, "ARTIFACT_MISSING", "Documento locale non disponibile: ripeti l'esportazione.");
                remove(path.resolve("input.zip")); remove(path.resolve("document.part"));
            }
        }
        cleanup();
    }
    @Scheduled(fixedDelayString = "${export.jobs.cleanup-interval:60000}")
    public synchronized void cleanup() {
        for (var job : List.copyOf(jobs.values())) if (job.terminal() && job.expiresAt().isBefore(Instant.now())) {
            if (deleteFolder(job.id())) jobs.remove(job.id());
        }
    }
    private long activeCount() { return jobs.values().stream().filter(j -> !j.terminal()).count(); }
    private long reservedBytes() throws IOException {
        long bytes;
        try (var paths = Files.walk(directory)) {
            bytes = 0;
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                try { bytes += Files.size(path); } catch (NoSuchFileException ignored) { /* atomic worker rename */ }
            }
        }
        // Active outputs can still grow to their maximum size.
        return bytes + activeCount() * config.maxOutputBytes();
    }
    private Path folder(UUID id) { return directory.resolve(id.toString()); }
    private boolean deleteFolder(UUID id) {
        try (var paths = Files.walk(folder(id))) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) remove(path); }
        catch (NoSuchFileException ignored) {} catch (IOException ex) { log.warn("Cannot clean job {}", id); }
        return !Files.exists(folder(id));
    }
    private void remove(Path path) { try { Files.deleteIfExists(path); } catch (IOException ex) { log.warn("Cannot remove job temporary file"); } }
    private ExportException busy() { return new ExportException(HttpStatus.TOO_MANY_REQUESTS, "JOB_QUEUE_FULL", "Coda di esportazione piena: riprova tra pochi secondi."); }
    @PreDestroy public void close() throws IOException {
        executor.shutdown();
        try { if (!executor.awaitTermination(15, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException ex) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
        // Never release the single-instance lock while a worker may still write.
        if (executor.isTerminated()) { lock.release(); lockChannel.close(); }
    }
}
