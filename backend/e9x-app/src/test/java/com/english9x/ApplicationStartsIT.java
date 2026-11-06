package com.english9x;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the whole app against PostgreSQL 16 as the container superuser and lets Flyway apply the
 * baseline migration. The owner/app role split arrives in P1.3.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationStartsIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestRestTemplate http;

    @Test
    void contextLoadsWithPostgres() {
        String version = jdbc.queryForObject("show server_version_num", String.class);
        assertTrue(version.startsWith("16"), "expected PostgreSQL 16, got " + version);

        Integer migrated = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success and version = '202611060001'",
                Integer.class);
        assertEquals(1, migrated);

        assertNotNull(jdbc.queryForObject("select to_regclass('public.organizations')", String.class));
    }

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<String> res = http.getForEntity("/actuator/health", String.class);
        assertEquals(200, res.getStatusCode().value());
        assertTrue(res.getBody().contains("\"status\":\"UP\""), res.getBody());
    }
}
