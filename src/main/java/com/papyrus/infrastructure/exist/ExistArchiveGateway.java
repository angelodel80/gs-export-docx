package com.papyrus.infrastructure.exist;

import com.papyrus.config.ExistProperties;
import com.papyrus.domain.XmlUnit;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Best-effort archive. Local conversion never reads its input back from the database. */
@Component
public class ExistArchiveGateway implements ArchiveGateway {
    private static final Logger log = LoggerFactory.getLogger(ExistArchiveGateway.class);
    private final ExistProperties config;
    private final HttpClient client;

    public ExistArchiveGateway(ExistProperties config) {
        this.config = config;
        if (config.timeout() == null || config.timeout().isNegative() || config.timeout().isZero()
                || config.archiveBudget() == null || config.archiveBudget().isNegative() || config.archiveBudget().isZero()) {
            throw new IllegalArgumentException("eXist timeouts must be positive");
        }
        if (config.enabled()) {
            URI url = config.restUrl();
            if (url == null || !Set.of("http", "https").contains(url.getScheme()) || url.getHost() == null
                    || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                    || !config.collection().matches("/db(?:/[A-Za-z0-9_-][A-Za-z0-9._-]*)+")) {
                throw new IllegalArgumentException("Invalid eXist REST URL or archive collection");
            }
            if (config.username() == null || config.username().isBlank() || config.username().contains(":")) {
                throw new IllegalArgumentException("Configure a dedicated eXist username when enabling the archive");
            }
        }
        client = HttpClient.newBuilder().connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Status archive(List<XmlUnit> units) {
        if (!config.enabled()) return Status.NOT_CONFIGURED;
        String name = "batch_" + UUID.randomUUID().toString().replace("-", "");
        String path = config.collection() + "/" + name;
        long deadline = System.nanoTime() + config.archiveBudget().toNanos();
        try {
            String xquery = "xquery version '3.1';\n"
                    + "import module namespace xmldb='http://exist-db.org/xquery/xmldb';\n"
                    + "if (xmldb:collection-available('" + config.collection() + "')) then "
                    + "let $created := xmldb:create-collection('" + config.collection() + "','" + name + "') "
                    + "return if (exists($created)) then <archive-created>" + name + "</archive-created> else () "
                    + "else error(xs:QName('archive-missing'), 'Archive collection is not provisioned')";
            byte[] response = request("POST", config.collection(), queryPayload(xquery), remaining(deadline));
            verifyCreated(response, name);
            for (XmlUnit unit : units) {
                request("PUT", path + "/" + unit.id() + ".xml", unit.originalXml(), remaining(deadline));
            }
            return Status.ARCHIVED;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            // The UUID isolates cleanup even if creation succeeded but its HTTP response was lost.
            if (!Thread.currentThread().isInterrupted()) {
                try { request("DELETE", path, null, Duration.ofMillis(250)); }
                catch (Exception cleanup) {
                    if (cleanup instanceof InterruptedException) Thread.currentThread().interrupt();
                    log.warn("eXist cleanup unconfirmed for batch {}", name);
                }
            }
            log.warn("eXist archive unavailable for batch {} ({})", name, ex.getClass().getSimpleName());
            return Status.UNAVAILABLE;
        }
    }

    private Duration remaining(long deadline) throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IOException("Archive budget exceeded");
        return Duration.ofNanos(Math.min(nanos, config.timeout().toNanos()));
    }

    private byte[] request(String method, String path, byte[] payload, Duration timeout)
            throws IOException, InterruptedException {
        String base = config.restUrl().toString().replaceAll("/+$", "");
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                .header("Content-Type", "application/xml")
                .header("Accept", "application/xml");
        String auth = config.username() + ":" + Objects.requireNonNullElse(config.password(), "");
        request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)));
        request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(payload));
        var future = client.sendAsync(request.build(), info -> new LimitedBodySubscriber(65536));
        try {
            var response = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("Archive HTTP error");
            return response.body();
        } catch (ExecutionException | TimeoutException ex) {
            throw new IOException("Archive request failed or timed out", ex);
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    private byte[] queryPayload(String query) {
        return ("<query xmlns=\"http://exist.sourceforge.net/NS/exist\" max=\"1\"><text><![CDATA["
                + query + "]]></text></query>").getBytes(StandardCharsets.UTF_8);
    }

    private void verifyCreated(byte[] response, String name) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(response));
        var elements = document.getElementsByTagName("archive-created");
        if (elements.getLength() != 1 || !elements.item(0).getTextContent().equals(name)) {
            throw new IOException("Archive collection creation not confirmed");
        }
    }
}
