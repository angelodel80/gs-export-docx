package com.papyrus.application;

import com.papyrus.api.ExportException;
import com.papyrus.config.ExportProperties;
import com.papyrus.docx.DocumentRenderer;
import com.papyrus.infrastructure.exist.ArchiveGateway;
import com.papyrus.mapping.*;
import com.papyrus.xml.XmlPackageReader;
import java.io.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.nio.file.*;
import com.papyrus.config.AsyncExportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DocumentProcessorService {
    private static final Logger log = LoggerFactory.getLogger(DocumentProcessorService.class);
    private final XmlPackageReader reader;
    private final ArchiveGateway archive;
    private final Map<String, EditionMappingStrategy> mappings = new HashMap<>();
    private final Map<String, TextConversionStrategy> conversions = new HashMap<>();
    private final Map<String, DocumentRenderer> renderers = new HashMap<>();
    private final Semaphore permits;

    public DocumentProcessorService(XmlPackageReader reader, ArchiveGateway archive, ExportProperties limits,
            List<EditionMappingStrategy> mappingStrategies, List<TextConversionStrategy> conversionStrategies,
            List<DocumentRenderer> documentRenderers) {
        this.reader = reader;
        this.archive = archive;
        this.permits = new Semaphore(limits.maxConcurrent());
        for (var strategy : mappingStrategies) register(mappings, strategy.name(), strategy);
        for (var strategy : conversionStrategies) for (String name : strategy.names()) register(conversions, name, strategy);
        for (var renderer : documentRenderers) register(renderers, renderer.format(), renderer);
    }

    public record Result(byte[] bytes, String filename, String mediaType, int fragments, ArchiveGateway.Status archiveStatus) {}
    public record FileResult(String filename, String mediaType, int fragments) {}

    /** Async path: bounded parsing without retaining source bytes, direct output to disk. */
    public FileResult convertToFile(InputStream input, Path output, ExportProperties limits, AsyncExportProperties jobs)
            throws IOException, InterruptedException {
        var mapper = select(mappings, "GS_UNIT");
        var converter = select(conversions, "PRESERVE");
        var renderer = select(renderers, "DOCX");
        permits.acquire();
        try {
            var units = reader.read(input, jobs.inputLimits(limits), false, jobs.maxTextChars());
            var edition = mapper.map(units, converter, jobs.maxFragments());
            try (var file = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW);
                 var bounded = new FilterOutputStream(file) {
                     private long written;
                     @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
                     @Override public void write(byte[] bytes, int offset, int length) throws IOException { reserve(length); out.write(bytes, offset, length); }
                     private void reserve(int length) {
                         if (length > jobs.maxOutputBytes() - written) throw ExportException.tooLarge("DOCX oltre il limite configurato.");
                         written += length;
                     }
                 }) {
                renderer.render(edition, bounded);
            }
            return new FileResult(edition.id() + "." + renderer.extension(), renderer.mediaType(), edition.fragments().size());
        } finally { permits.release(); }
    }

    public Result convert(InputStream input, String mapping, String conversion, String format, boolean archiveRequested)
            throws IOException {
        var mapper = select(mappings, mapping);
        var converter = select(conversions, conversion);
        var renderer = select(renderers, format);
        if (!permits.tryAcquire()) throw new ExportException(HttpStatus.TOO_MANY_REQUESTS, "BUSY", "Conversioni occupate: riprova tra pochi secondi.");
        try {
            var units = reader.read(input);
            var edition = mapper.map(units, converter);
            byte[] bytes = renderer.render(edition);
            ArchiveGateway.Status status = ArchiveGateway.Status.SKIPPED;
            if (archiveRequested) {
                try { status = archive.archive(units); }
                catch (RuntimeException ex) {
                    log.warn("Optional archive failed ({})", ex.getClass().getSimpleName());
                    status = ArchiveGateway.Status.UNAVAILABLE;
                }
            }
            return new Result(bytes, edition.id() + "." + renderer.extension(), renderer.mediaType(), edition.fragments().size(), status);
        } finally {
            permits.release();
        }
    }

    private static <T> void register(Map<String, T> registry, String key, T value) {
        if (registry.putIfAbsent(key, value) != null) throw new IllegalStateException("Duplicate strategy: " + key);
    }

    private static <T> T select(Map<String, T> registry, String name) {
        T value = registry.get(name);
        if (value == null) throw new ExportException(HttpStatus.BAD_REQUEST, "UNKNOWN_STRATEGY", "Strategia non supportata: " + name);
        return value;
    }
}
