package com.papyrus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.papyrus.api.ExportException;
import com.papyrus.application.DocumentProcessorService;
import com.papyrus.config.*;
import com.papyrus.jobs.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class ExportJobServiceTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final ExportProperties limits = new ExportProperties(256, 2000000, 16000000, 64, 100, 2, 10000, 20000, "Greek", "Latin");
    AsyncExportProperties config(Duration retention, long disk) {
        return new AsyncExportProperties(directory, 1, 1, 8, 1000000, 1024, disk, retention, 256, 2000000, 16000000, 100, 10000, 1000000);
    }
    DocumentProcessorService processor(CountDownLatch started, CountDownLatch release) throws Exception {
        var processor = mock(DocumentProcessorService.class);
        when(processor.convertToFile(any(), any(), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test wait expired");
            Files.write((Path) invocation.getArgument(1), "document".getBytes());
            return new DocumentProcessorService.FileResult("pherc-1025.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 2);
        });
        return processor;
    }
    ExportJobService create(AsyncExportProperties config, DocumentProcessorService processor, DeliveryStrategy delivery) throws IOException {
        return new ExportJobService(config, limits, processor, json, List.of(delivery));
    }
    void ready(ExportJobService service, UUID id) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(service.status(id).state()).isEqualTo(JobSnapshot.State.READY));
    }
    @Test @Timeout(10) void actualStatesQueueBoundsAndIndependentJobs() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var service = create(config(Duration.ofHours(2), 10000000), processor(started, release), new DownloadDeliveryStrategy());
        try {
            var first = service.submit(ExportIntegrationTest.file(ExportIntegrationTest.fixture()), "DOWNLOAD");
            assertThat(first.state()).isEqualTo(JobSnapshot.State.ACCEPTED);
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(service.status(first.id()).state()).isEqualTo(JobSnapshot.State.TRANSFORMING);
            assertThatThrownBy(() -> service.openDocument(first.id())).isInstanceOfSatisfying(ExportException.class,
                    ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
            var second = service.submit(ExportIntegrationTest.file(ExportIntegrationTest.fixture()), "DOWNLOAD");
            assertThat(service.status(second.id()).state()).isEqualTo(JobSnapshot.State.ACCEPTED);
            assertThatThrownBy(() -> service.submit(ExportIntegrationTest.file(ExportIntegrationTest.fixture()), "DOWNLOAD"))
                    .isInstanceOfSatisfying(ExportException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
            release.countDown(); ready(service, first.id()); ready(service, second.id());
            assertThat(service.status(first.id()).resultUrl()).contains(first.id().toString());
            assertThat(service.status(second.id()).resultUrl()).contains(second.id().toString());
        } finally { release.countDown(); service.close(); }
    }
    @Test void readyJobAndItsDownloadSurviveServiceRestart() throws Exception {
        var processor = processor(new CountDownLatch(0), new CountDownLatch(0));
        var service = create(config(Duration.ofHours(2), 10000000), processor, new DownloadDeliveryStrategy());
        UUID id;
        try { id = service.submit(ExportIntegrationTest.file(ExportIntegrationTest.fixture()), "DOWNLOAD").id(); ready(service, id); }
        finally { service.close(); }
        var restored = create(config(Duration.ofHours(2), 10000000), processor, new DownloadDeliveryStrategy());
        try (var input = restored.openDocument(id)) {
            assertThat(new String(input.readAllBytes())).isEqualTo("document");
            assertThat(restored.status(id).state()).isEqualTo(JobSnapshot.State.READY);
        } finally { restored.close(); }
    }
    @Test void interruptedWorkIsRecoveredAsAnExplicitFailure() throws Exception {
        UUID id = UUID.randomUUID(); Path folder = Files.createDirectory(directory.resolve(id.toString()));
        var pending = new JobSnapshot(id, JobSnapshot.State.TRANSFORMING, "DOWNLOAD", Instant.now(), Instant.now(), null, null,
                null, null, null, null, null, null, null, null);
        json.writeValue(folder.resolve("job.json").toFile(), pending); Files.writeString(folder.resolve("input.zip"), "partial");
        Files.writeString(folder.resolve("document.part"), "partial");
        var service = create(config(Duration.ofHours(2), 10000000), processor(new CountDownLatch(0), new CountDownLatch(0)), new DownloadDeliveryStrategy());
        try {
            assertThat(service.status(id).state()).isEqualTo(JobSnapshot.State.FAILED);
            assertThat(service.status(id).errorCode()).isEqualTo("SERVER_RESTARTED");
            assertThat(folder.resolve("input.zip")).doesNotExist(); assertThat(folder.resolve("document.part")).doesNotExist();
        } finally { service.close(); }
    }
    @Test void expiredResultsAreRemovedAndDiskQuotaRejectsBeforeSpooling() throws Exception {
        var processor = processor(new CountDownLatch(0), new CountDownLatch(0));
        var service = create(config(Duration.ofMillis(100), 10000000), processor, new DownloadDeliveryStrategy());
        UUID id;
        try {
            id = service.submit(ExportIntegrationTest.file(ExportIntegrationTest.fixture()), "DOWNLOAD").id();
            await().atMost(Duration.ofSeconds(5)).until(() -> {
                var node = json.readTree(directory.resolve(id.toString()).resolve("job.json").toFile());
                return node.get("state").asText().equals("READY");
            });
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                service.cleanup(); assertThat(directory.resolve(id.toString())).doesNotExist();
            });
        } finally { service.close(); }
        var full = create(config(Duration.ofHours(2), 100), processor, new DownloadDeliveryStrategy());
        try {
            assertThatThrownBy(() -> full.submit(ExportIntegrationTest.file(ExportIntegrationTest.fixture()), "DOWNLOAD"))
                    .isInstanceOfSatisfying(ExportException.class, ex -> assertThat(ex.code()).isEqualTo("JOB_STORAGE_FULL"));
        } finally { full.close(); }
    }
    @Test void customUrlDeliveryIsRegisteredWithoutChangingTheExecutor() throws Exception {
        DeliveryStrategy strategy = new DeliveryStrategy() {
            public String name() { return "CUSTOM"; }
            public Result deliver(UUID id, Path document, String filename) { return new Result("CUSTOM", "https://documents.example/" + filename, null); }
        };
        var service = create(config(Duration.ofHours(2), 10000000), processor(new CountDownLatch(0), new CountDownLatch(0)), strategy);
        try {
            UUID id = service.submit(ExportIntegrationTest.file(ExportIntegrationTest.fixture()), "CUSTOM").id(); ready(service, id);
            assertThat(service.status(id).resultUrl()).isEqualTo("https://documents.example/pherc-1025.docx");
        } finally { service.close(); }
    }

    @Test void midRenderFailureDeletesPartialOutputAndNeverOffersReady() throws Exception {
        var processor = mock(DocumentProcessorService.class);
        when(processor.convertToFile(any(), any(), any(), any())).thenAnswer(invocation -> {
            Files.writeString((Path) invocation.getArgument(1), "incomplete"); throw new IOException("simulated disk failure");
        });
        var service = create(config(Duration.ofHours(2), 10000000), processor, new DownloadDeliveryStrategy());
        try {
            var id = service.submit(ExportIntegrationTest.file(ExportIntegrationTest.fixture()), "DOWNLOAD").id();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(service.status(id).state()).isEqualTo(JobSnapshot.State.FAILED);
                assertThat(directory.resolve(id.toString()).resolve("document.part")).doesNotExist();
                assertThat(directory.resolve(id.toString()).resolve("input.zip")).doesNotExist();
            });
            assertThat(service.status(id).resultUrl()).isNull();
        } finally { service.close(); }
    }

    @Test void restartCleansOwnedOrphanUploadsButPreservesUnrecognizedDirectories() throws Exception {
        UUID orphanId = UUID.randomUUID(); Path orphan = Files.createDirectory(directory.resolve(orphanId.toString()));
        Files.writeString(orphan.resolve(".job-owner"), orphanId.toString()); Files.writeString(orphan.resolve("input.zip"), "incomplete");
        Path unknown = Files.createDirectory(directory.resolve(UUID.randomUUID().toString()));
        Files.writeString(unknown.resolve("user.txt"), "preserve");
        var service = create(config(Duration.ofHours(2), 10000000), processor(new CountDownLatch(0), new CountDownLatch(0)), new DownloadDeliveryStrategy());
        try { assertThat(orphan).doesNotExist(); assertThat(Files.readString(unknown.resolve("user.txt"))).isEqualTo("preserve"); }
        finally { service.close(); }
    }
}
