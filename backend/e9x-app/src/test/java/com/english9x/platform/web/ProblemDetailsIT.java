package com.english9x.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestAuth;
import com.english9x.support.TestPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Every error leaves the API as RFC 7807 application/problem+json with type, title, status, detail, errors[]. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("problem-test")
class ProblemDetailsIT {

    static final String DB = TestPostgres.createDatabase();
    static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired
    TestRestTemplate http;

    private ResponseEntity<String> send(HttpMethod method, String path, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(TestAuth.accessToken(TestPostgres.someUser(DB))); // /api/** needs a token; the endpoints under test ignore who it is
        return http.exchange(path, method, new HttpEntity<>(body, h), String.class);
    }

    private JsonNode problem(ResponseEntity<String> r, int status) throws Exception {
        assertEquals(status, r.getStatusCode().value(), r.getBody());
        MediaType ct = r.getHeaders().getContentType();
        assertNotNull(ct, "content type");
        assertTrue(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(ct), "content type was " + ct);
        JsonNode p = JSON.readTree(r.getBody());
        for (String k : List.of("type", "title", "status", "detail", "errors")) {
            assertTrue(p.has(k), "problem has " + k + ": " + r.getBody());
        }
        assertEquals(status, p.get("status").asInt());
        assertTrue(p.get("errors").isArray(), "errors is an array");
        assertFalse(p.get("type").asText().isBlank());
        return p;
    }

    @Test
    void validationErrorsListFields() throws Exception {
        JsonNode p = problem(send(HttpMethod.POST, "/api/problem-test/form",
                "{\"name\":\"\",\"email\":\"not-an-email\",\"rows\":[]}"), 400);
        List<String> fields = new ArrayList<>();
        p.get("errors").forEach(e -> {
            fields.add(e.get("field").asText());
            assertFalse(e.get("message").asText().isBlank());
            assertTrue(e.get("row").isNull(), "a top-level field has no row");
        });
        assertTrue(fields.containsAll(List.of("name", "email")), "fields: " + fields);
        assertEquals(2, fields.size(), "fields: " + fields);
        assertTrue(p.get("type").asText().endsWith("validation-failed"));
    }

    @Test
    void validationErrorsListRows() throws Exception {
        JsonNode p = problem(send(HttpMethod.POST, "/api/problem-test/form",
                "{\"name\":\"n\",\"email\":\"a@b.vn\",\"rows\":[{\"sku\":\"A\",\"qty\":1},{\"sku\":\"\",\"qty\":0}]}"), 400);
        List<String> seen = new ArrayList<>();
        p.get("errors").forEach(e -> seen.add(e.get("row").asInt() + ":" + e.get("field").asText()));
        assertTrue(seen.contains("1:rows[1].sku") && seen.contains("1:rows[1].qty"), "row-indexed errors: " + seen);
        assertEquals(2, seen.size(), "row 0 is valid: " + seen);
    }

    @Test
    void malformedBodyIs400WithoutParserDetails() throws Exception {
        ResponseEntity<String> r = send(HttpMethod.POST, "/api/problem-test/form", "{not json");
        JsonNode p = problem(r, 400);
        assertFalse(r.getBody().contains("Unexpected character"), "no parser internals: " + r.getBody());
        assertEquals(0, p.get("errors").size());
    }

    @Test
    void unknownPathIs404() throws Exception {
        problem(send(HttpMethod.GET, "/no/such/path", null), 404);
    }

    @Test
    void explicitNotFoundIs404() throws Exception {
        problem(send(HttpMethod.GET, "/api/problem-test/missing", null), 404);
    }

    @Test
    void wrongMethodIs405() throws Exception {
        ResponseEntity<String> r = send(HttpMethod.DELETE, "/api/problem-test/missing", null);
        problem(r, 405);
        assertNotNull(r.getHeaders().getAllow(), "Allow header is kept");
    }

    @Test
    void orgContextDeniedIs403AndLeaksNothing() throws Exception {
        ResponseEntity<String> r = send(HttpMethod.GET, "/api/problem-test/denied", null);
        problem(r, 403);
        assertFalse(r.getBody().contains("organisation"), "no ids or messages: " + r.getBody());
        assertFalse(r.getBody().matches("(?s).*[0-9a-f]{8}-[0-9a-f]{4}-.*"), "no uuid: " + r.getBody());
    }

    @Test
    void wrappedOrgContextDeniedIsAlso403() throws Exception {
        ResponseEntity<String> r = send(HttpMethod.GET, "/api/problem-test/denied-wrapped", null);
        problem(r, 403);
        assertFalse(r.getBody().contains("EntityManager"), r.getBody());
    }

    @Test
    void unexpectedErrorIs500AndLeaksNoInternals() throws Exception {
        ResponseEntity<String> r = send(HttpMethod.GET, "/api/problem-test/boom", null);
        JsonNode p = problem(r, 500);
        String body = r.getBody();
        for (String secret : List.of("secret-internal-detail", "jdbc:", "hunter2", "IllegalStateException", "ProblemTestEndpoints", "\tat ")) {
            assertFalse(body.contains(secret), "leaked '" + secret + "': " + body);
        }
        assertFalse(p.has("trace") || p.has("stackTrace") || p.has("exception"), body);
    }
}
