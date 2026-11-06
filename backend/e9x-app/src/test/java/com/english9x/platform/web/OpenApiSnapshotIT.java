package com.english9x.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The OpenAPI description of the running app is committed as frontend/openapi/openapi.json; the frontend generates
 * its API client from that file (pnpm run gen-api). This test fails when the committed snapshot is stale.
 * Refresh it with: {@code mvn -pl e9x-app -am verify -Dit.test=OpenApiSnapshotIT -De9x.openapi.update=true}
 * (or {@code scripts/refresh-openapi.sh}).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "springdoc.api-docs.enabled=true")
class OpenApiSnapshotIT {

    static final String DB = TestPostgres.createDatabase();
    static final Path SNAPSHOT = Path.of("..", "..", "frontend", "openapi", "openapi.json").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired
    TestRestTemplate http;

    /** Sorted keys, no volatile parts: the server URL holds the random test port. */
    private static String normalise(String apiDocs) throws Exception {
        JsonNode tree = JSON.readTree(apiDocs);
        ((ObjectNode) tree).remove("servers");
        Object sorted = JSON.treeToValue(tree, Object.class);
        return JSON.writeValueAsString(sorted) + "\n";
    }

    @Test
    void committedSnapshotMatchesTheRunningApp() throws Exception {
        String live = normalise(http.getForObject("/v3/api-docs", String.class));
        assertTrue(live.contains("/api/v1/platform/ping") && live.contains("\"Problem\""), "spec is the real one: " + live);
        if (Boolean.getBoolean("e9x.openapi.update")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, live);
        }
        assertTrue(Files.exists(SNAPSHOT), "missing " + SNAPSHOT + "; create it with -De9x.openapi.update=true");
        assertEquals(Files.readString(SNAPSHOT), live,
                "frontend/openapi/openapi.json is stale; refresh with scripts/refresh-openapi.sh and run pnpm -C frontend run gen-api");
    }
}
