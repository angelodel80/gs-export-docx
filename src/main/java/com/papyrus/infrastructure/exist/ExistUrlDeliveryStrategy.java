package com.papyrus.infrastructure.exist;

import com.papyrus.config.ExistProperties;
import com.papyrus.jobs.DeliveryStrategy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ExistUrlDeliveryStrategy implements DeliveryStrategy {
    private static final Logger log = LoggerFactory.getLogger(ExistUrlDeliveryStrategy.class);
    private final ExistProperties config;
    private final ExistArchiveGateway gateway;
    private final URI publicRestUrl;
    private final Duration timeout;

    public ExistUrlDeliveryStrategy(ExistProperties config, ExistArchiveGateway gateway,
            @Value("${existdb.documents.public-rest-url:}") String publicUrl,
            @Value("${existdb.documents.timeout:2m}") Duration timeout) {
        this.config = config; this.gateway = gateway; this.timeout = timeout;
        publicRestUrl = publicUrl.isBlank() ? config.restUrl() : URI.create(publicUrl);
        if (publicRestUrl == null || !Set.of("http", "https").contains(publicRestUrl.getScheme())
                || publicRestUrl.getHost() == null || publicRestUrl.getUserInfo() != null
                || publicRestUrl.getQuery() != null || publicRestUrl.getFragment() != null
                || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid eXist document delivery configuration");
    }
    public String name() { return "EXIST_URL"; }
    public boolean available() { return config.enabled(); }

    public Result deliver(UUID id, Path document, String filename) {
        String batch = "job_" + id.toString().replace("-", "");
        String path = config.collection() + "/" + batch;
        try {
            gateway.createDocumentBatch(batch);
            gateway.putDocument(path + "/" + filename, document, timeout);
            String url = publicRestUrl.toString().replaceAll("/+$", "") + path + "/" + filename;
            return new Result(name(), url, null);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            if (!Thread.currentThread().isInterrupted()) {
                try { gateway.removeDocumentBatch(path); }
                catch (Exception cleanup) {
                    if (cleanup instanceof InterruptedException) Thread.currentThread().interrupt();
                    log.warn("Document batch cleanup unconfirmed for {}", batch);
                }
            }
            log.warn("Document archive failed for job {} ({})", id, ex.getClass().getSimpleName());
            return new Result("DOWNLOAD", DeliveryStrategy.downloadUrl(id), "eXist non disponibile: il DOCX è pronto per il download locale.");
        }
    }
}
