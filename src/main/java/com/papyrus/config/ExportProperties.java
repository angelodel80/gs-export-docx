package com.papyrus.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("export")
public record ExportProperties(@Min(1) int maxEntries, @Min(1) long maxEntryBytes,
        @Min(1) long maxExpandedBytes, @Min(1) int maxXmlDepth,
        @Min(1) int maxStructuralUnits, @Min(1) int maxConcurrent,
        @Min(1) int maxTextLines, @Min(1) int maxLineChars,
        @NotBlank String greekFont, @NotBlank String latinFont) {}
