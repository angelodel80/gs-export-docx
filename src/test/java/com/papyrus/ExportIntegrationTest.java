package com.papyrus;

import com.papyrus.api.ExportException;
import com.papyrus.application.DocumentProcessorService;
import com.papyrus.config.ExportProperties;
import com.papyrus.domain.*;
import com.papyrus.docx.DocumentRenderer;
import com.papyrus.infrastructure.exist.ArchiveGateway;
import com.papyrus.mapping.*;
import com.papyrus.xml.XmlPackageReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.papyrus.mapping.GsUnitMappingStrategy.*;

@SpringBootTest(properties = {"existdb.enabled=true", "existdb.rest-url=http://127.0.0.1:1/exist/rest",
        "existdb.username=export-test", "existdb.password=", "existdb.timeout=100ms", "existdb.archive-budget=200ms"})
@AutoConfigureMockMvc
class ExportIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired XmlPackageReader reader;
    @Autowired GsUnitMappingStrategy mapping;
    @Autowired DocumentProcessorService service;
    @Autowired ExportProperties limits;

    static byte[] fixture() throws IOException {
        try (var in = ExportIntegrationTest.class.getResourceAsStream("/fixtures/pherc-1025.zip")) {
            return Objects.requireNonNull(in).readAllBytes();
        }
    }
    static String cellText(XWPFTableCell cell) {
        return String.join("\n", cell.getParagraphs().stream().map(XWPFParagraph::getText).toList());
    }
    static MockMultipartFile file(byte[] bytes) { return new MockMultipartFile("file", "package.zip", "application/zip", bytes); }
    static byte[] zip(String... nameContent) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (int i = 0; i < nameContent.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameContent[i]));
                zip.write(nameContent[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test void realPackageProducesTwoFragmentsWithAllTwelveContentsUnchanged() throws Exception {
        var units = reader.read(new ByteArrayInputStream(fixture()));
        assertThat(units).hasSize(15);
        var edition = mapping.map(units, new PreserveTextStrategy());
        assertThat(edition.id()).isEqualTo("pherc-1025");
        assertThat(edition.fragments()).hasSize(2);
        assertThat(edition.fragments().get(0).id()).startsWith("fr-01");
        assertThat(edition.fragments().get(1).id()).startsWith("fr-02");
        var result = service.convert(new ByteArrayInputStream(fixture()), "GS_UNIT", "PRESERVE", "DOCX", false);
        assertThat(result.archiveStatus()).isEqualTo(ArchiveGateway.Status.SKIPPED);
        try (var document = new XWPFDocument(new ByteArrayInputStream(result.bytes()));
             var reference = new XWPFDocument(getClass().getResourceAsStream("/fixtures/PHerc.1025-reference.docx"))) {
            assertThat(document.getTables()).hasSize(reference.getTables().size()).hasSize(4);
            for (int i = 0; i < 2; i++) {
                var fragment = edition.fragments().get(i);
                var left = document.getTables().get(i * 2);
                var right = document.getTables().get(i * 2 + 1);
                assertThat(left.getRow(0).getTableCells()).hasSize(3);
                assertThat(right.getRow(1).getTableCells()).hasSize(1);
                assertThat(right.getRow(1).getCell(0).getCTTc().getTcPr().getGridSpan().getVal().intValue()).isEqualTo(3);
                assertThat(cellText(left.getRow(0).getCell(2))).isEqualTo(fragment.text(DIPLOMATIC));
                assertThat(cellText(left.getRow(0).getCell(0))).isEqualTo(fragment.text(SOURCES) + "\n\n" + fragment.text(PALEOGRAPHIC));
                assertThat(cellText(right.getRow(0).getCell(1))).isEqualTo(fragment.text(LITERARY));
                assertThat(cellText(right.getRow(0).getCell(2))).isEqualTo(fragment.text(TRANSLATION));
                assertThat(cellText(right.getRow(1).getCell(0))).isEqualTo(fragment.text(PHILOLOGICAL));
                var numbers = left.getRow(0).getCell(1).getParagraphs().stream().map(XWPFParagraph::getText).toList();
                assertThat(numbers).contains("5", "10", i == 0 ? "16" : "11");
            }
            assertThat(document.getParagraphs().stream().filter(XWPFParagraph::isPageBreak).count()).isEqualTo(3);
            assertThat(document.getStyles().getStyle("GreekText").getCTStyle().getRPr().getRFontsArray(0).getHAnsi()).isEqualTo("IFAO-Grec Unicode");
            String text = String.join("\n", document.getTables().stream().map(XWPFTable::getText).toList());
            assertThat(text).contains("ϲ", "\uE5CE", "[", "]").doesNotContain("P.Oxy.", "εὐαγγελίου");
        }
    }

    @Test void unavailableDatabaseStillDownloadsTheRealDocx() throws Exception {
        var response = mvc.perform(multipart("/api/papyrus/upload").file(file(fixture())).param("archive", "true"))
                .andExpect(status().isOk()).andExpect(header().string("X-Archive-Status", "UNAVAILABLE"))
                .andExpect(header().string("X-Fragment-Count", "2"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andReturn().getResponse();
        try (var doc = new XWPFDocument(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(doc.getTables()).hasSize(4);
            assertThat(doc.getTables().get(0).getText()).contains("1025", "ϲ").doesNotContain("P.Oxy.");
        }
    }

    @Test void frontendAndHealthAreServedOnTheSameOrigin() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("/app.mjs")));
        mvc.perform(get("/api.mjs")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test void unknownStrategyAndMissingFileAreExplicitErrors() throws Exception {
        mvc.perform(multipart("/api/papyrus/upload").file(file(fixture())).param("granularity", "DESTROY"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_STRATEGY"));
        mvc.perform(multipart("/api/papyrus/upload"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test void emptyZipAndNonZipDoNotBecomeDemoDocuments() throws Exception {
        for (byte[] bytes : List.of(zip(), "not zip".getBytes(StandardCharsets.UTF_8))) {
            mvc.perform(multipart("/api/papyrus/upload").file(file(bytes)))
                    .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("INVALID_ZIP"));
        }
    }

    @Test void malformedXmlDtdUnsafeNamesAndNestedContentAreRejected() throws Exception {
        for (byte[] bytes : List.of(zip("x.xml", "<unit>"),
                zip("../x.xml", "<unit/>"),
                zip("x.xml", "<!DOCTYPE unit [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><unit><id>&x;</id></unit>"),
                zip("x.xml", "<unit><id>x</id><dsl><language>gs-sources</language><content><b>text</b></content></dsl></unit>"))) {
            mvc.perform(multipart("/api/papyrus/upload").file(file(bytes)))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_PACKAGE"));
        }
    }

    @Test void expandedBytesAndDirectoryDataAreBounded() throws Exception {
        var small = new ExportProperties(256, 40, 100, 64, 100, 2, 10000, 20000, "Greek", "Latin");
        var bounded = new XmlPackageReader(small);
        assertThatThrownBy(() -> bounded.read(new ByteArrayInputStream(zip("x.xml", "x".repeat(200)))))
                .isInstanceOfSatisfying(ExportException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        assertThatThrownBy(() -> bounded.read(new ByteArrayInputStream(zip("dir/", "x".repeat(200)))))
                .isInstanceOfSatisfying(ExportException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test void textLineCountLineLengthAndXmlDepthAreLimitedBeforeRendering() throws Exception {
        var small = new ExportProperties(256, 2097152, 16777216, 4, 100, 2, 2, 4, "Greek", "Latin");
        var bounded = new XmlPackageReader(small);
        for (String content : List.of("ϲ\n\n", " ϲ ϲ ")) {
            String xml = "<unit><id>x</id><dsl><language>gs-sources</language><content>" + content + "</content></dsl></unit>";
            assertThatThrownBy(() -> bounded.read(new ByteArrayInputStream(zip("x.xml", xml))))
                    .isInstanceOfSatisfying(ExportException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        }
        String deep = "<unit><id>x</id><a><b><c><d/></c></b></a></unit>";
        assertThatThrownBy(() -> bounded.read(new ByteArrayInputStream(zip("x.xml", deep))))
                .isInstanceOfSatisfying(ExportException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test void missingResourcesAndWrongHttpMethodKeepTheirOwnStatus() throws Exception {
        mvc.perform(get("/missing-file.css")).andExpect(status().isNotFound());
        mvc.perform(get("/api/papyrus/upload")).andExpect(status().isMethodNotAllowed());
    }

    @Test void missingTextDuplicateIdsBrokenLinksAndDisconnectedCyclesAreRejected() throws Exception {
        var original = reader.read(new ByteArrayInputStream(fixture()));
        var missing = new ArrayList<>(original);
        missing.removeIf(u -> u.id().equals("fr-01-cl-00-pz-02-cr-04-pherc-1025-lit-trad"));
        var duplicate = new ArrayList<>(original);
        duplicate.add(original.get(0));
        var broken = new ArrayList<>(original);
        var u = broken.get(0);
        broken.set(0, new XmlUnit(u.id(), u.title(), u.creator(), "missing", u.children(), u.language(), u.content(), u.originalXml()));
        var cycle = new ArrayList<>(original);
        cycle.add(new XmlUnit("a", "", "", "b", List.of("b"), null, null, new byte[0]));
        cycle.add(new XmlUnit("b", "", "", "a", List.of("a"), null, null, new byte[0]));
        for (var units : List.of(missing, duplicate, broken, cycle)) {
            assertThatThrownBy(() -> mapping.map(units, new PreserveTextStrategy())).isInstanceOf(ExportException.class);
        }
    }

    @Test void legacyStrategiesPreserveEveryCharacterIncludingTabsAndSupplementaryUnicode() {
        String content = "  ϲ[α̣]  \t\n\n  \uD835\uDEFC\uE5CE  \n";
        var strategy = new PreserveTextStrategy();
        assertThat(strategy.names()).contains("PRESERVE", "FULL_TEXT", "CHAR_LEVEL");
        assertThat(strategy.convert(DIPLOMATIC, content)).isEqualTo(content);
    }

    @Test void tabsAndEmptyLinesSurviveWordEncoding() throws Exception {
        var renderer = new com.papyrus.docx.WordStyles(limits);
        try (var doc = new XWPFDocument()) {
            renderer.initialize(doc);
            var cell = renderer.table(doc, 9638).getRow(0).getCell(0);
            String raw = "  ϲ\t  α̣  \n\n \n";
            renderer.content(cell, raw, "GreekText");
            var out = new ByteArrayOutputStream(); doc.write(out);
            try (var reopened = new XWPFDocument(new ByteArrayInputStream(out.toByteArray()))) {
                assertThat(cellText(reopened.getTables().get(0).getRow(0).getCell(0))).isEqualTo(raw);
            }
        }
    }

    @Test @Timeout(10) void concurrencyLimitRejectsExcessWorkAndNewRendererCanBeRegistered() throws Exception {
        var started = new CountDownLatch(1); var finish = new CountDownLatch(1);
        DocumentRenderer custom = new DocumentRenderer() {
            public String format() { return "CUSTOM"; }
            public String mediaType() { return "text/plain"; }
            public String extension() { return "txt"; }
            public byte[] render(Edition edition) throws IOException {
                started.countDown();
                try { if (!finish.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out in test"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                return edition.id().getBytes(StandardCharsets.UTF_8);
            }
        };
        var one = new ExportProperties(256, 2097152, 16777216, 64, 100, 1, 10000, 20000, "Greek", "Latin");
        var processor = new DocumentProcessorService(reader, units -> ArchiveGateway.Status.SKIPPED, one,
                List.of(mapping), List.of(new PreserveTextStrategy()), List.of(custom));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> processor.convert(new ByteArrayInputStream(fixture()), "GS_UNIT", "PRESERVE", "CUSTOM", false));
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> processor.convert(new ByteArrayInputStream(fixture()), "GS_UNIT", "PRESERVE", "CUSTOM", false))
                    .isInstanceOfSatisfying(ExportException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
            finish.countDown();
            assertThat(new String(future.get(3, TimeUnit.SECONDS).bytes(), StandardCharsets.UTF_8)).isEqualTo("pherc-1025");
        } finally { finish.countDown(); executor.shutdownNow(); }
    }
}
