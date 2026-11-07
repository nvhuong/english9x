package com.english9x.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestAuth;
import com.english9x.support.TestPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The React app is served from the jar (src/test/resources/static/index.html stands in for the Vite build).
 * Browser navigations to deep links (/cms, /preferences) get index.html so the client router can take over; the
 * API and actuator keep real 404s, and a missing static asset is a 404, not HTML.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpaFallbackIT {

    static final String DB = TestPostgres.createDatabase();
    static final String BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired
    TestRestTemplate http;

    private ResponseEntity<String> get(String path, String accept) {
        return get(path, accept, null);
    }

    private ResponseEntity<String> get(String path, String accept, String bearer) {
        HttpHeaders h = new HttpHeaders();
        if (accept != null) {
            h.set(HttpHeaders.ACCEPT, accept);
        }
        if (bearer != null) {
            h.setBearerAuth(bearer);
        }
        ResponseEntity<String> r = http.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
        return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders()).body(r.getBody() == null ? "" : r.getBody());
    }

    private static void assertIndexHtml(ResponseEntity<String> r) {
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        assertTrue(r.getBody().contains("spa-test-index"), "index.html expected: " + r.getBody());
        MediaType ct = r.getHeaders().getContentType();
        assertNotNull(ct);
        assertTrue(MediaType.TEXT_HTML.isCompatibleWith(ct), "content type was " + ct);
    }

    @Test
    void rootServesIndexHtml() {
        assertIndexHtml(get("/", BROWSER_ACCEPT));
    }

    @Test
    void deepLinksWithHtmlAcceptServeIndexHtml() {
        assertIndexHtml(get("/cms", BROWSER_ACCEPT));
        assertIndexHtml(get("/preferences", BROWSER_ACCEPT));
        assertIndexHtml(get("/cms/questions/123", BROWSER_ACCEPT));
        assertIndexHtml(get("/preferences", "text/html"));
    }

    @Test
    void apiPathsKeepTheirRealProblem404() {
        String token = TestAuth.accessToken(TestPostgres.someUser(DB));
        for (String path : new String[] {"/api/nope", "/api/platform/nope", "/api"}) {
            for (String accept : new String[] {BROWSER_ACCEPT, "application/json"}) {
                // anonymous callers are refused before routing (401); an authenticated one gets the real 404 problem
                ResponseEntity<String> anonymous = get(path, accept);
                assertEquals(401, anonymous.getStatusCode().value(), path + " / " + accept + ": " + anonymous.getBody());
                assertFalse(anonymous.getBody().contains("spa-test-index"), path + " must not be the SPA");
                ResponseEntity<String> r = get(path, accept, token);
                assertEquals(404, r.getStatusCode().value(), path + " / " + accept + ": " + r.getBody());
                assertFalse(r.getBody().contains("spa-test-index"), path + " must not be the SPA");
            }
        }
        ResponseEntity<String> r = get("/api/nope", "application/json", token);
        MediaType ct = r.getHeaders().getContentType();
        assertNotNull(ct);
        assertTrue(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(ct), "content type was " + ct);
    }

    @Test
    void actuatorPathsKeepTheirReal404() {
        // only health is exposed; everything else under /actuator is denied (401 anonymous), never the SPA
        ResponseEntity<String> nope = get("/actuator/nope", BROWSER_ACCEPT);
        assertEquals(401, nope.getStatusCode().value(), nope.getBody());
        assertFalse(nope.getBody().contains("spa-test-index"));
        ResponseEntity<String> r = get("/actuator/health/nope", BROWSER_ACCEPT);
        assertEquals(404, r.getStatusCode().value(), "/actuator/health/nope: " + r.getBody());
        assertFalse(r.getBody().contains("spa-test-index"), "must not be the SPA");
        assertEquals(200, get("/actuator/health", BROWSER_ACCEPT).getStatusCode().value(), "real endpoints still work");
    }

    @Test
    void missingStaticAssetsAre404NotHtml() {
        for (String path : new String[] {"/assets/nope.js", "/assets/nope.css", "/favicon-missing.ico", "/fonts/x.woff2"}) {
            ResponseEntity<String> r = get(path, BROWSER_ACCEPT);
            assertEquals(404, r.getStatusCode().value(), path + ": " + r.getBody());
            assertFalse(r.getBody().contains("spa-test-index"), path + " must not be the SPA");
        }
    }

    @Test
    void nonHtmlRequestsToUnknownPathsAre404() {
        for (String accept : new String[] {"application/json", "image/png", null}) {
            ResponseEntity<String> r = get("/preferences", accept);
            // no Accept header means "anything": not a browser navigation, so no HTML fallback either
            assertEquals(404, r.getStatusCode().value(), accept + ": " + r.getBody());
            assertFalse(r.getBody().contains("spa-test-index"));
        }
    }

    @Test
    void onlyGetFallsBack() {
        ResponseEntity<String> r = http.exchange("/preferences", HttpMethod.POST, new HttpEntity<>("{}", htmlHeaders()), String.class);
        assertTrue(r.getStatusCode().value() >= 400 && r.getStatusCode().value() < 500, "was " + r.getStatusCode());
        assertFalse(r.getBody() != null && r.getBody().contains("spa-test-index"));
    }

    private static HttpHeaders htmlHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.ACCEPT, BROWSER_ACCEPT);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
