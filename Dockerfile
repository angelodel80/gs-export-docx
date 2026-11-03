# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
# Optional secrets support corporate/session proxies without baking their configuration into the image.
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=maven_settings \
    --mount=type=secret,id=java_truststore \
    set -eu; \
    set --; \
    if [ -f /run/secrets/maven_settings ]; then set -- "$@" -s /run/secrets/maven_settings; fi; \
    if [ -f /run/secrets/java_truststore ]; then set -- "$@" -Djavax.net.ssl.trustStore=/run/secrets/java_truststore; fi; \
    mvn -B -ntp "$@" -DskipTests package

FROM eclipse-temurin:17-jre-jammy AS runtime
LABEL org.opencontainers.image.source="https://github.com/angelodel80/gs-export-docx" \
      org.opencontainers.image.title="gs-export-docx" \
      org.opencontainers.image.licenses="MIT"
WORKDIR /app
RUN mkdir -p /data/jobs /data/uploads && chown -R 10001:10001 /data
COPY --from=build --chown=10001:10001 /build/target/gs-export-docx.jar /app/app.jar
COPY --from=build --chown=10001:10001 /build/target/classes/com/papyrus/Healthcheck.class /app/health/com/papyrus/Healthcheck.class
USER 10001:10001
ENV PORT=8081
ENV EXPORT_JOBS_DIRECTORY=/data/jobs EXPORT_UPLOAD_DIRECTORY=/data/uploads
VOLUME ["/data"]
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD ["java", "-Xmx32m", "-cp", "/app/health", "com.papyrus.Healthcheck"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
