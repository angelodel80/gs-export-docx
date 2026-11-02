package com.papyrus;

import java.net.*;
import java.nio.charset.StandardCharsets;

/** Standalone, dependency-free probe copied separately into the JRE image. */
public final class Healthcheck {
    private Healthcheck() {}
    public static void main(String[] args) throws Exception {
        String port = System.getenv().getOrDefault("PORT", "8081");
        var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/actuator/health/liveness")
                .toURL().openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        try {
            if (connection.getResponseCode() != 200) System.exit(1);
            try (var input = connection.getInputStream()) {
                String body = new String(input.readNBytes(4096), StandardCharsets.UTF_8);
                if (!body.contains("\"status\":\"UP\"")) System.exit(1);
            }
        } finally { connection.disconnect(); }
    }
}
