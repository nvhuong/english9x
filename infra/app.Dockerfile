# Multi-stage: React build -> Spring Boot jar (serves the build as static content) -> slim JRE.
# Build context is the repository root:  docker build -f infra/app.Dockerfile .
FROM node:22.23.4-alpine3.24@sha256:0e1754758c2df4cfa3e68d59e2e1b7374bb7fd9ea6328eaaacf15cc934af8202 AS web
WORKDIR /web
RUN corepack enable
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm run build

FROM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS api
WORKDIR /src
COPY backend/ backend/
COPY --from=web /web/dist/ backend/e9x-app/src/main/resources/static/
RUN mvn -B -q -f backend/pom.xml -pl e9x-app -am package -DskipTests -DskipITs

# ffprobe reads the duration of every uploaded audio and video file (P2.2). One static binary, pinned by digest; the test wrapper
# backend/e9x-app/src/test/scripts/ffprobe-docker.sh runs the same image, and MediaInfraFilesTest fails if the two pins differ.
FROM mwader/static-ffmpeg:7.1.1@sha256:11a44711684c0b9f754c047dcd64235b8b52deab251bd0e0a86f22faa160749c AS ffmpeg

FROM eclipse-temurin:21.0.13_9-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a
RUN useradd --system --uid 10001 e9x
COPY --from=ffmpeg /ffprobe /usr/local/bin/ffprobe
WORKDIR /app
COPY --from=api /src/backend/e9x-app/target/e9x-app-*-SNAPSHOT.jar /app/app.jar
USER e9x
EXPOSE 8080
# ExitOnOutOfMemoryError: worker threads catch Throwable, so without it a JVM out of memory would limp on; restart: unless-stopped brings it back clean.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
