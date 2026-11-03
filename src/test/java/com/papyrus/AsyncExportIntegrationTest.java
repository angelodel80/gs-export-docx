package com.papyrus;

import com.fasterxml.jackson.databind.*;
import com.papyrus.jobs.JobSnapshot;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AsyncExportIntegrationTest {
    static final Path DIRECTORY;
    static { try { DIRECTORY = Files.createTempDirectory("docx-integration-"); } catch (IOException e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry properties) {
        properties.add("export.jobs.directory", () -> DIRECTORY.toString());
        properties.add("existdb.enabled", () -> "false");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired com.papyrus.application.DocumentProcessorService processor;
    @Autowired com.papyrus.config.ExportProperties limits;
    @Autowired com.papyrus.config.AsyncExportProperties settings;

    JsonNode submit(byte[] zip) throws Exception {
        var response = mvc.perform(multipart("/api/papyrus/jobs").file(ExportIntegrationTest.file(zip)))
                .andExpect(status().isAccepted()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.state").value("ACCEPTED")).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray());
    }
    JsonNode completed(String id) {
        final JsonNode[] result = new JsonNode[1];
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            result[0] = json.readTree(mvc.perform(get("/api/papyrus/jobs/" + id)).andReturn().getResponse().getContentAsByteArray());
            assertThat(result[0].get("state").asText()).isIn("READY", "FAILED");
        });
        return result[0];
    }
    @Test void acceptedTransformationAndReadyLeadToARealStreamingDownload() throws Exception {
        var accepted = submit(ExportIntegrationTest.fixture()); String id = accepted.get("id").asText();
        var ready = completed(id);
        assertThat(ready.get("state").asText()).isEqualTo("READY");
        assertThat(ready.get("transformationStartedAt").isNull()).isFalse();
        assertThat(ready.get("completedAt").isNull()).isFalse();
        assertThat(ready.get("fragments").asInt()).isEqualTo(2);
        assertThat(ready.get("delivery").asText()).isEqualTo("DOWNLOAD");
        byte[] bytes = mvc.perform(get(ready.get("resultUrl").asText())).andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andReturn().getResponse().getContentAsByteArray();
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertThat(doc.getTables()).hasSize(4);
            assertThat(doc.getTables().get(0).getText()).contains("1025", "ϲ", "\uE5CE").doesNotContain("P.Oxy.");
        }
        assertThat(DIRECTORY.resolve(id).resolve("input.zip")).doesNotExist();
    }
    @Test void invalidHierarchyFailsTheJobWithoutOfferingAPartialDocument() throws Exception {
        var accepted = submit(ExportIntegrationTest.zip("broken.xml", "<unit><id>root</id><children/></unit>"));
        String id = accepted.get("id").asText();
        assertThat(completed(id).get("state").asText()).isEqualTo("FAILED");
        mvc.perform(get("/api/papyrus/jobs/" + id + "/document")).andExpect(status().isConflict());
        assertThat(DIRECTORY.resolve(id).resolve("document.docx")).doesNotExist();
    }
    @Test void urlModeIsNotAcceptedWithoutExistAndUnknownJobsReturn404() throws Exception {
        mvc.perform(get("/api/papyrus/jobs/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries[0]").value("DOWNLOAD"));
        mvc.perform(multipart("/api/papyrus/jobs").file(ExportIntegrationTest.file(ExportIntegrationTest.fixture())).param("delivery", "EXIST_URL"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DELIVERY_UNAVAILABLE"));
        mvc.perform(get("/api/papyrus/jobs/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }
    @Test void packageAboveTheSyncLimitIsConvertedByTheAsyncPath() throws Exception {
        // Stored ZIP with real XML plus harmless comments: > 8 MiB input without fabricating text units.
        var out = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(new ByteArrayInputStream(ExportIntegrationTest.fixture()));
             var zip = new ZipOutputStream(out)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                byte[] original = input.readAllBytes();
                var content = new ByteArrayOutputStream(); content.write(original);
                content.write(("<!--" + "x".repeat(650000) + "-->").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                byte[] data = content.toByteArray(); var stored = new ZipEntry(entry.getName());
                var crc = new CRC32(); crc.update(data); stored.setMethod(ZipEntry.STORED);
                stored.setSize(data.length); stored.setCompressedSize(data.length); stored.setCrc(crc.getValue());
                zip.putNextEntry(stored); zip.write(data); zip.closeEntry();
            }
        }
        byte[] large = out.toByteArray(); assertThat(large.length).isGreaterThan(8 * 1024 * 1024);
        mvc.perform(multipart("/api/papyrus/upload").file(ExportIntegrationTest.file(large))).andExpect(status().isPayloadTooLarge());
        var ready = completed(submit(large).get("id").asText());
        assertThat(ready.get("state").asText()).isEqualTo("READY");
        assertThat(ready.get("fragments").asInt()).isEqualTo(2);
    }

    @Test void rendererCannotWritePastTheOutputBudget() throws Exception {
        var bounded = new com.papyrus.config.AsyncExportProperties(DIRECTORY, 1, 4, 16, settings.maxUploadBytes(),
                100, settings.maxDiskBytes(), settings.retention(), settings.maxEntries(), settings.maxEntryBytes(),
                settings.maxExpandedBytes(), settings.maxFragments(), settings.maxTextLines(), settings.maxTextChars());
        Path output = DIRECTORY.resolve("bounded-output.part");
        try {
            assertThatThrownBy(() -> processor.convertToFile(new ByteArrayInputStream(ExportIntegrationTest.fixture()), output, limits, bounded))
                    .isInstanceOf(Exception.class);
            assertThat(Files.size(output)).isLessThanOrEqualTo(100);
        } finally { Files.deleteIfExists(output); }
    }
}
