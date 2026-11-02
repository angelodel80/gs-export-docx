package com.papyrus.infrastructure.exist;

import com.papyrus.config.ExistProperties;
import com.papyrus.domain.XmlUnit;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class ExistArchiveGatewayTest {
    private HttpServer server;
    private ExecutorService executor;
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<byte[]> stored = new CopyOnWriteArrayList<>();
    private final AtomicInteger failPut = new AtomicInteger();
    private boolean queryError;
    private boolean slowBody;
    private boolean largeBody;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                String method = exchange.getRequestMethod();
                methods.add(method + " " + exchange.getRequestURI().getPath());
                byte[] input = exchange.getRequestBody().readAllBytes();
                if (method.equals("POST")) {
                    String query = new String(input, StandardCharsets.UTF_8);
                    var matcher = Pattern.compile("batch_[a-f0-9]{32}").matcher(query);
                    if (!matcher.find()) throw new IOException("Batch not found");
                    byte[] body = (largeBody ? "x".repeat(70000) : queryError ? "<exception>bad query</exception>" :
                            "<exist:result xmlns:exist='http://exist.sourceforge.net/NS/exist'><archive-created>" + matcher.group() + "</archive-created></exist:result>")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    if (slowBody) { try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                    exchange.getResponseBody().write(body);
                } else if (method.equals("PUT")) {
                    if (failPut.get() > 0 && stored.size() + 1 == failPut.get()) exchange.sendResponseHeaders(503, -1);
                    else { stored.add(input); exchange.sendResponseHeaders(201, -1); }
                } else exchange.sendResponseHeaders(204, -1);
            } catch (IOException ignored) { /* client may cancel the deliberate slow response */ }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }

    ExistArchiveGateway gateway(boolean enabled, Duration timeout) {
        var url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/exist/rest");
        return new ExistArchiveGateway(new ExistProperties(enabled, url, "export-test", "", "/db/test", timeout, Duration.ofSeconds(2)));
    }
    List<XmlUnit> units() {
        return List.of(new XmlUnit("one", "", "", "", List.of(), null, null, "<unit> ϲ </unit>".getBytes(StandardCharsets.UTF_8)),
                new XmlUnit("two", "", "", "", List.of(), null, null, "<unit>  α̣  </unit>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void disabledArchiveDoesNotContactDatabase() {
        assertThat(gateway(false, Duration.ofSeconds(1)).archive(units())).isEqualTo(ArchiveGateway.Status.NOT_CONFIGURED);
        assertThat(methods).isEmpty();
    }
    @Test void successfulArchiveStoresOriginalBytesUnderAnIsolatedBatch() {
        assertThat(gateway(true, Duration.ofSeconds(1)).archive(units())).isEqualTo(ArchiveGateway.Status.ARCHIVED);
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0)).isEqualTo(units().get(0).originalXml());
        assertThat(stored.get(1)).isEqualTo(units().get(1).originalXml());
        assertThat(methods.get(0)).isEqualTo("POST /exist/rest/db/test");
        assertThat(methods.get(1)).matches("PUT /exist/rest/db/test/batch_[a-f0-9]{32}/one.xml");
        assertThat(methods).noneMatch(s -> s.startsWith("DELETE"));
    }
    @Test void partialUploadIsCleanedUpAndReportedUnavailable() {
        failPut.set(2);
        assertThat(gateway(true, Duration.ofSeconds(1)).archive(units())).isEqualTo(ArchiveGateway.Status.UNAVAILABLE);
        assertThat(stored).hasSize(1);
        assertThat(methods.get(methods.size() - 1)).matches("DELETE /exist/rest/db/test/batch_[a-f0-9]{32}");
    }
    @Test void queryErrorReturnedWithHttp200IsNotASuccessfulArchive() {
        queryError = true;
        assertThat(gateway(true, Duration.ofSeconds(1)).archive(units())).isEqualTo(ArchiveGateway.Status.UNAVAILABLE);
        assertThat(stored).isEmpty();
    }
    @Test @Timeout(3) void timeoutIncludesTheResponseBodyNotJustHeaders() {
        slowBody = true;
        long start = System.nanoTime();
        assertThat(gateway(true, Duration.ofMillis(150)).archive(units())).isEqualTo(ArchiveGateway.Status.UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }
    @Test void oversizedArchiveResponseIsRejectedBeforeXmlParsing() {
        largeBody = true;
        assertThat(gateway(true, Duration.ofSeconds(1)).archive(units())).isEqualTo(ArchiveGateway.Status.UNAVAILABLE);
        assertThat(stored).isEmpty();
    }
}
