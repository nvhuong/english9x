# Multi-stage: React build -> Spring Boot jar (serves the build as static content) -> slim JRE.
# Build context is the repository root:  docker build -f infra/app.Dockerfile .
FROM node:22-alpine AS web
WORKDIR /web
RUN corepack enable
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm run build

FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /src
COPY backend/ backend/
COPY --from=web /web/dist/ backend/e9x-app/src/main/resources/static/
RUN mvn -B -q -f backend/pom.xml -pl e9x-app -am package -DskipTests -DskipITs

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 e9x
WORKDIR /app
COPY --from=api /src/backend/e9x-app/target/e9x-app-*-SNAPSHOT.jar /app/app.jar
USER e9x
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
