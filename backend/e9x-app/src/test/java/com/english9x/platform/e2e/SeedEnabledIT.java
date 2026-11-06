package com.english9x.platform.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * With the "e2e" profile the seed API is there, and it writes as the table owner (the login Flyway uses), not the
 * runtime login and not a BYPASSRLS role. The RLS policies and the runtime login are untouched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
class SeedEnabledIT {

    static final String DB = TestPostgres.createDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void seedBeansExistWithTheProfile() {
        assertEquals(1, context.getBeanNamesForType(SeedController.class).length);
        assertEquals(1, context.getBeanNamesForType(SeedDatabase.class).length);
    }

    @Test
    void pingReportsTheSeedLoginAndItIsNotBypassRls() {
        ResponseEntity<String> r = http.postForEntity("/api/test/seed/ping", null, String.class);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        assertTrue(r.getBody().contains("\"seedLogin\":\"e9x_owner\""), r.getBody());
        assertEquals(
                0,
                new JdbcTemplate(TestPostgres.superuser(DB))
                        .queryForObject("select count(*) from pg_roles where rolname = 'e9x_owner' and rolbypassrls", Integer.class));
    }

    @Test
    void theRuntimeLoginIsStillTheRestrictedOne() {
        assertEquals("e9x_app_login", jdbc.queryForObject("select current_user", String.class));
    }
}
