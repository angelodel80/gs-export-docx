package com.papyrus.config;

import jakarta.validation.constraints.*;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("export.jobs")
public record AsyncExportProperties(@NotNull Path directory, @Min(1) int workers, @Min(1) int queueCapacity,
        @Min(1) int maxStoredJobs, @Min(1) long maxUploadBytes, @Min(1) long maxOutputBytes,
        @Min(1) long maxDiskBytes, @NotNull Duration retention,
        @Min(1) int maxEntries, @Min(1) long maxEntryBytes, @Min(1) long maxExpandedBytes,
        @Min(1) int maxFragments, @Min(1) int maxTextLines, @Min(1) long maxTextChars) {
    public AsyncExportProperties {
        if (retention == null || retention.isNegative() || retention.isZero()) throw new IllegalArgumentException("Job retention must be positive");
    }
    public ExportProperties inputLimits(ExportProperties base) {
        return new ExportProperties(maxEntries, maxEntryBytes, maxExpandedBytes, base.maxXmlDepth(),
                maxFragments, base.maxConcurrent(), maxTextLines, base.maxLineChars(), base.greekFont(), base.latinFont());
    }
}
