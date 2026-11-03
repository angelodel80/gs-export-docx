package com.papyrus.jobs;

import java.nio.file.Path;
import java.util.UUID;

/** Delivery can be extended without changing the job executor or rendering pipeline. */
public interface DeliveryStrategy {
    String name();
    default boolean available() { return true; }
    Result deliver(UUID jobId, Path document, String filename) throws Exception;
    record Result(String mode, String url, String warning) {}
    static String downloadUrl(UUID jobId) { return "/api/papyrus/jobs/" + jobId + "/document"; }
}
