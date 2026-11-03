package com.papyrus.infrastructure.exist;

import com.papyrus.config.ExistProperties;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExistUrlDeliveryStrategyTest {
    @TempDir Path folder;
    HttpServer server; ExecutorService executor;
    byte[] stored; String mime; boolean fail; boolean invalidStoredType;
    final List<String> methods = new CopyOnWriteArrayList<>();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                methods.add(exchange.getRequestMethod());
                if (exchange.getRequestMethod().equals("POST")) {
                    var query = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    var matcher = Pattern.compile("job_[a-f0-9]{32}").matcher(query); assertThat(matcher.find()).isTrue();
                    byte[] response = ("<result><archive-created>" + matcher.group() + "</archive-created></result>").getBytes();
                    exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
                } else if (exchange.getRequestMethod().equals("PUT")) {
                    mime = exchange.getRequestHeaders().getFirst("Content-Type"); stored = exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(fail ? 503 : 201, -1);
                } else if (exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseHeaders().set("Content-Type", invalidStoredType ? "application/xml" : mime);
                    exchange.getResponseHeaders().set("Content-Length", Integer.toString(stored.length));
                    exchange.sendResponseHeaders(200, -1);
                } else exchange.sendResponseHeaders(204, -1);
            }
        }); server.start();
    }
    @AfterEach void close() { server.stop(0); executor.shutdownNow(); }
    ExistUrlDeliveryStrategy strategy() {
        var config = new ExistProperties(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/exist/rest"),
                "test", "", "/db/exports", Duration.ofSeconds(1), Duration.ofSeconds(2));
        return new ExistUrlDeliveryStrategy(config, new ExistArchiveGateway(config), "https://public.example/exist/rest", Duration.ofSeconds(2));
    }
    Path document() throws Exception {
        Path file = folder.resolve("edition.docx");
        try (var input = getClass().getResourceAsStream("/fixtures/PHerc.1025-reference.docx")) { Files.copy(input, file); }
        return file;
    }
    @Test void binaryDocumentIsUploadedAndVerifiedBeforeReturningThePublicUrl() throws Exception {
        UUID id = UUID.randomUUID(); Path file = document(); var result = strategy().deliver(id, file, "pherc-1025.docx");
        assertThat(result.mode()).isEqualTo("EXIST_URL");
        assertThat(result.url()).isEqualTo("https://public.example/exist/rest/db/exports/job_" + id.toString().replace("-", "") + "/pherc-1025.docx");
        assertThat(mime).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(stored).isEqualTo(Files.readAllBytes(file)); assertThat(methods).containsExactly("POST", "PUT", "HEAD");
    }
    @Test void failedStorageKeepsTheLocalDownloadAndCleansItsOwnBatch() throws Exception {
        fail = true; UUID id = UUID.randomUUID(); var result = strategy().deliver(id, document(), "pherc-1025.docx");
        assertThat(result.mode()).isEqualTo("DOWNLOAD"); assertThat(result.url()).contains(id.toString());
        assertThat(result.warning()).isNotBlank(); assertThat(methods).containsExactly("POST", "PUT", "DELETE");
    }
    @Test void anXmlErrorResourceCannotBeOfferedAsAStoredDocx() throws Exception {
        invalidStoredType = true; var result = strategy().deliver(UUID.randomUUID(), document(), "pherc-1025.docx");
        assertThat(result.mode()).isEqualTo("DOWNLOAD"); assertThat(methods).containsExactly("POST", "PUT", "HEAD", "DELETE");
    }
}
