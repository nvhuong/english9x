package com.english9x.platform.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * With the "e2e" profile the seed API is there, behind a shared secret, and it writes as the table owner (the login
 * Flyway uses), not the runtime login and not a BYPASSRLS role. The RLS policies and the runtime login are untouched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
class SeedEnabledIT {

    static final String DB = TestPostgres.createDatabase();
    static final String SECRET = "e2e-seed-secret-for-tests";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
        registry.add("e9x.domain", () -> "localhost");
        registry.add("e9x.public-base-url", () -> "https://localhost");
        registry.add("e9x.e2e.seed-secret", () -> SECRET);
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    private ResponseEntity<String> post(String path, String body, String secret) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (secret != null) {
            h.add("X-E2E-Seed-Secret", secret);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    @Test
    void reviewEventsGoThroughTheRealRegistryAndListenersToTheRecipients() throws Exception {
        String reviewer = post("/api/test/seed/learner", "{\"email\":\"seed-reviewer@e2e.test\"}", SECRET).getBody().replaceAll(".*\"userId\":\"([^\"]+)\".*", "$1");
        String author = post("/api/test/seed/learner", "{\"email\":\"seed-author@e2e.test\"}", SECRET).getBody().replaceAll(".*\"userId\":\"([^\"]+)\".*", "$1");
        assertEquals(201, post("/api/test/seed/staff-grant", "{\"userId\":\"" + reviewer + "\",\"role\":\"REVIEWER\",\"examCode\":\"IELTS\"}", SECRET).getStatusCode().value());
        ResponseEntity<String> requested = post("/api/test/seed/review-event", "{\"kind\":\"requested\",\"examCode\":\"IELTS\",\"authorId\":\"" + author + "\"}", SECRET);
        assertEquals(201, requested.getStatusCode().value(), requested.getBody());
        assertEquals(201, post("/api/test/seed/review-event", "{\"kind\":\"approved\",\"examCode\":\"IELTS\",\"authorId\":\"" + author + "\"}", SECRET).getStatusCode().value());
        assertEquals(400, post("/api/test/seed/review-event", "{\"kind\":\"nope\",\"authorId\":\"" + author + "\"}", SECRET).getStatusCode().value());
        JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            assertEquals(1, su.queryForObject("select count(*) from notifications where user_id = ?::uuid and event_type = 'REVIEW_REQUESTED'", Integer.class, reviewer));
            assertEquals(1, su.queryForObject("select count(*) from notifications where user_id = ?::uuid and event_type = 'CONTENT_PUBLISHED'", Integer.class, author));
        });
    }

    @Test
    void reviewFlowRunsTheRealServiceAndLeavesAuditRecords() {
        String author = post("/api/test/seed/learner", "{\"email\":\"flow-author@e2e.test\"}", SECRET).getBody().replaceAll(".*\"userId\":\"([^\"]+)\".*", "$1");
        String reviewer = post("/api/test/seed/learner", "{\"email\":\"flow-reviewer@e2e.test\"}", SECRET).getBody().replaceAll(".*\"userId\":\"([^\"]+)\".*", "$1");
        // nobody holds a role yet: the real Authz refuses, so the seed cannot be used to bypass the rules
        ResponseEntity<String> refused = post("/api/test/seed/review-flow", "{\"authorId\":\"" + author + "\",\"reviewerId\":\"" + reviewer + "\"}", SECRET);
        assertTrue(refused.getStatusCode().value() >= 400, refused.getBody());
        assertEquals(201, post("/api/test/seed/staff-grant", "{\"userId\":\"" + author + "\",\"role\":\"AUTHOR\",\"examCode\":\"IELTS\"}", SECRET).getStatusCode().value());
        assertEquals(201, post("/api/test/seed/staff-grant", "{\"userId\":\"" + reviewer + "\",\"role\":\"REVIEWER\",\"examCode\":\"IELTS\"}", SECRET).getStatusCode().value());
        ResponseEntity<String> ok = post("/api/test/seed/review-flow", "{\"authorId\":\"" + author + "\",\"reviewerId\":\"" + reviewer + "\",\"outcome\":\"approved\"}", SECRET);
        assertEquals(201, ok.getStatusCode().value(), ok.getBody());
        String reviewId = ok.getBody().replaceAll(".*\"reviewId\":\"([^\"]+)\".*", "$1");
        JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));
        assertEquals(author, su.queryForObject("select actor_id::text from audit_log where action = 'review.submit' and target_id = ?", String.class, reviewId));
        assertEquals(reviewer, su.queryForObject("select actor_id::text from audit_log where action = 'review.approve' and target_id = ?", String.class, reviewId));
        assertEquals("PUBLISHED", su.queryForObject("select d.status from e2e_docs d join review_requests r on r.version_id = d.id where r.id = ?::uuid", String.class, reviewId));
    }

    @Test
    void organisationFixturesFollowTheRealRules() {
        ResponseEntity<String> org = post("/api/test/seed/organization", "{\"name\":\"Seed Centre\",\"type\":\"ENTERPRISE\"}", SECRET);
        assertEquals(201, org.getStatusCode().value(), org.getBody());
        String orgId = org.getBody().replaceAll(".*\"orgId\":\"([^\"]+)\".*", "$1");
        ResponseEntity<String> learner = post("/api/test/seed/learner", "{\"email\":\"seed-org-member@e2e.test\"}", SECRET);
        String userId = learner.getBody().replaceAll(".*\"userId\":\"([^\"]+)\".*", "$1");
        String roles = "[{\"role\":\"ORG_ADMIN\"},{\"role\":\"LEARNER\"}]";
        assertEquals(201, post("/api/test/seed/membership", "{\"orgId\":\"" + orgId + "\",\"userId\":\"" + userId + "\",\"roles\":" + roles + "}", SECRET).getStatusCode().value());
        assertEquals(2, new JdbcTemplate(TestPostgres.superuser(DB)).queryForObject(
                "select count(*) from role_grants where org_id = ?::uuid and user_id = ?::uuid", Integer.class, orgId, userId));
        // the seed writes as the owner, but the database rules still hold: an ENTERPRISE cannot get a TEACHER
        ResponseEntity<String> bad = post("/api/test/seed/membership", "{\"orgId\":\"" + orgId + "\",\"userId\":\"" + userId
                + "\",\"roles\":[{\"role\":\"TEACHER\",\"examCode\":\"IELTS\"}]}", SECRET);
        assertTrue(bad.getStatusCode().value() >= 400, bad.getBody());
        ResponseEntity<String> staff = post("/api/test/seed/staff-grant", "{\"userId\":\"" + userId + "\",\"role\":\"SYS_ADMIN\"}", SECRET);
        assertEquals(201, staff.getStatusCode().value(), staff.getBody());
    }

    @Test
    void seedBeansExistWithTheProfile() {
        assertEquals(1, context.getBeanNamesForType(SeedController.class).length);
        assertEquals(1, context.getBeanNamesForType(SeedDatabase.class).length);
    }

    @Test
    void pingNeedsTheSecretAndReportsTheSeedLoginWhichIsNotBypassRls() {
        assertEquals(403, post("/api/test/seed/ping", null, null).getStatusCode().value(), "no secret");
        assertEquals(403, post("/api/test/seed/ping", null, "wrong").getStatusCode().value(), "wrong secret");
        ResponseEntity<String> r = post("/api/test/seed/ping", null, SECRET);
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

    @Test
    void forbiddenAnswerIsProblemJson() {
        ResponseEntity<String> r = post("/api/test/seed/learner", "{}", "nope");
        assertEquals(403, r.getStatusCode().value());
        assertTrue(r.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON), r.getHeaders().toString());
    }

    /**
     * Security and MVC match the DECODED path, so a guard that compared the raw URI could be walked around by spelling
     * the same route differently. Whatever the spelling, if it reaches a seed controller it needs the secret.
     */
    @Test
    void noSpellingOfASeedRouteGetsPastTheSecret() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        for (String path : new String[] {"/api/test/%73eed/ping", "/api/test/s%65ed/learner", "/api/test/seed/%70ing", "/%61pi/test/seed/ping",
                "/api/%74est/seed/ping", "/api/test/seed/ping;x=y", "/api/test/seed/ping/", "/api/test/seed//ping", "/api/test/./seed/ping",
                "/api/test/seed/%6cearner", "/api/test/%53EED/ping"}) {
            java.net.http.HttpResponse<String> r = client.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path))
                            .header("Content-Type", "application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertTrue(r.statusCode() >= 400 && r.statusCode() != 500, "POST " + path + " without the secret answered " + r.statusCode() + ": " + r.body());
            assertFalse(r.body().contains("seedLogin") || r.body().contains("userId"), "POST " + path + " reached the seed code: " + r.body());
        }
    }

    @Test
    void seededLearnersCanSignInAndTheGuardSeesVerification() {
        ResponseEntity<String> verified = post("/api/test/seed/learner", "{\"email\":\"seeded-v@e2e.test\",\"password\":\"Seeded-pass-1\"}", SECRET);
        assertEquals(201, verified.getStatusCode().value(), verified.getBody());
        ResponseEntity<String> login = post("/api/auth/login", "{\"email\":\"seeded-v@e2e.test\",\"password\":\"Seeded-pass-1\"}", null);
        assertEquals(200, login.getStatusCode().value(), login.getBody());

        ResponseEntity<String> unverified = post("/api/test/seed/learner", "{\"email\":\"seeded-u@e2e.test\",\"verified\":false}", SECRET);
        assertEquals(201, unverified.getStatusCode().value(), unverified.getBody());
        JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB)); // the runtime login cannot see other people's rows
        String vId = su.queryForObject("select id::text from users where email = 'seeded-v@e2e.test'", String.class);
        String uId = su.queryForObject("select id::text from users where email = 'seeded-u@e2e.test'", String.class);
        assertEquals(200, post("/api/test/seed/require-verified", "{\"userId\":\"" + vId + "\"}", SECRET).getStatusCode().value());
        ResponseEntity<String> refused = post("/api/test/seed/require-verified", "{\"userId\":\"" + uId + "\"}", SECRET);
        assertEquals(403, refused.getStatusCode().value(), refused.getBody());
        assertTrue(refused.getBody().contains("urn:e9x:problem:email-not-verified"), refused.getBody());
    }
}
