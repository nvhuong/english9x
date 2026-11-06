package com.english9x;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the whole app against PostgreSQL 16 with the production role split: Flyway migrates as the table owner
 * e9x_owner, the application connects as e9x_app_login.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationStartsIT {

    static final String DB = TestPostgres.createDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired
    JdbcTemplate jdbc;

    /** flyway_schema_history belongs to e9x_owner: the runtime login cannot even read it, so look as superuser. */
    final JdbcTemplate superuser = new JdbcTemplate(TestPostgres.superuser(DB));

    @Autowired
    TestRestTemplate http;

    @Test
    void contextLoadsWithPostgres() {
        String version = jdbc.queryForObject("show server_version_num", String.class);
        assertTrue(version.startsWith("16"), "expected PostgreSQL 16, got " + version);

        assertEquals(
                2,
                superuser.queryForObject(
                        "select count(*) from flyway_schema_history where success and version in ('202611060001','202611060002')",
                        Integer.class));
        assertNotNull(jdbc.queryForObject("select to_regclass('public.organizations')", String.class));
    }

    @Test
    void appConnectsAsTheLoginRoleAndFlywayRanAsTheOwner() {
        assertEquals("e9x_app_login", jdbc.queryForObject("select current_user", String.class));
        assertEquals(
                0,
                superuser.queryForObject(
                        "select count(*) from flyway_schema_history where installed_by <> 'e9x_owner'", Integer.class),
                "migrations must run as e9x_owner");
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from pg_tables where schemaname = 'public' and tableowner <> 'e9x_owner'",
                        Integer.class),
                "every table is owned by e9x_owner");
    }

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<String> res = http.getForEntity("/actuator/health", String.class);
        assertEquals(200, res.getStatusCode().value());
        assertTrue(res.getBody().contains("\"status\":\"UP\""), res.getBody());
    }
}
