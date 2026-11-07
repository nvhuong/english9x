package com.english9x;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The clean-checkout run measured this: with the mail host of infra/.env.example the site works and readiness is UP, yet /actuator/health, the README's
 * first check, answered 503. Mail is retried and never blocks a request, so a mail server that is away does not make the application down.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthWithoutMailIT {

    static final String DB = TestPostgres.createDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
        registry.add("spring.mail.host", () -> "mail.invalid");
    }

    @Autowired
    TestRestTemplate http;

    @Test
    void anUnreachableMailServerLeavesTheHealthEndpointUp() {
        ResponseEntity<String> r = http.getForEntity("/actuator/health", String.class);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        assertTrue(r.getBody().contains("\"UP\""), r.getBody());
    }
}
