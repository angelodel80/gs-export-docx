package com.papyrus.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("existdb")
public record ExistProperties(boolean enabled, URI restUrl, String username, String password,
        String collection, Duration timeout, Duration archiveBudget) {}
