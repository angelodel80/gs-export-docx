package com.papyrus.jobs;

import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DownloadDeliveryStrategy implements DeliveryStrategy {
    public String name() { return "DOWNLOAD"; }
    public Result deliver(UUID jobId, Path document, String filename) {
        return new Result(name(), DeliveryStrategy.downloadUrl(jobId), null);
    }
}
