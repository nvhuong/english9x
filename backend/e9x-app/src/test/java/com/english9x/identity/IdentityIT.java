package com.english9x.identity;

import com.english9x.support.SmtpSink;
import com.english9x.support.TestAuth;
import com.english9x.support.TestPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared harness of the identity integration tests: one PostgreSQL database (so all subclasses with the same
 * configuration share one application context), a real SMTP sink for the mail, and a tiny JSON-over-HTTP client.
 * Tests use unique emails instead of cleaning up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IdentityIT {

    static final String DB = TestPostgres.createDatabase();
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PASSWORD = "Correct-horse-1";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired
    protected TestRestTemplate http;

    /** Sees past Row-Level Security: for fixtures and assertions only. */
    protected final JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));

    protected static SmtpSink smtp() {
        return TestAuth.SMTP;
    }

    protected record Res(int status, JsonNode body, HttpHeaders headers, String raw) {
        String problemType() {
            return body.path("type").asText();
        }
    }

    protected Res send(HttpMethod method, String path, Object body, String bearer, Map<String, String> headers) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            h.setBearerAuth(bearer);
        }
        headers.forEach(h::add);
        try {
            String json = body == null ? null : (body instanceof String s ? s : JSON.writeValueAsString(body));
            ResponseEntity<String> r = http.exchange(path, method, new HttpEntity<>(json, h), String.class);
            String raw = r.getBody() == null ? "" : r.getBody();
            JsonNode node = raw.isBlank() || !raw.trim().startsWith("{") ? JSON.createObjectNode() : JSON.readTree(raw);
            return new Res(r.getStatusCode().value(), node, r.getHeaders(), raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Res post(String path, Object body) {
        return send(HttpMethod.POST, path, body, null, Map.of());
    }

    protected Res post(String path, Object body, Map<String, String> headers) {
        return send(HttpMethod.POST, path, body, null, headers);
    }

    protected Res get(String path, String bearer) {
        return send(HttpMethod.GET, path, null, bearer, Map.of());
    }

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
    }

    protected Res register(String email, String password) {
        return post("/api/auth/register", Map.of("email", email, "password", password, "displayName", "Test User", "consent", true));
    }

    protected Res login(String email, String password) {
        return post("/api/auth/login", Map.of("email", email, "password", password));
    }

    /** Registers, follows the mail's link and returns once the address is verified. */
    protected String registerVerified(String email, String password) {
        SmtpSink.Mail mail = registerUnverified(email, password);
        Res v = post("/api/auth/verify-email", Map.of("token", mail.linkParam("token")));
        if (v.status() != 204) {
            throw new AssertionError("verify failed: " + v.raw());
        }
        return email;
    }

    protected SmtpSink.Mail registerUnverified(String email, String password) {
        Res r = register(email, password);
        if (r.status() != 201) {
            throw new AssertionError("register failed: " + r.status() + " " + r.raw());
        }
        return smtp().await(email);
    }

    protected UUID userId(String email) {
        return su.queryForObject("select id from users where lower(email) = lower(?)", UUID.class, email);
    }
}
