package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.sweep.ForeignIdSweep;
import com.english9x.org.sweep.SweepFixtures;
import com.english9x.support.TestAuth;
import com.english9x.support.TestPostgres;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Review Focus #1: a member of org A asks every GET endpoint with an id for rows of org B. The endpoint list comes
 * from /v3/api-docs, so endpoints added by later tasks are swept automatically; the foreign ids come from a catalog
 * query over every RLS table. The test-only controllers are described in {@code SweepEndpoints}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "springdoc.api-docs.enabled=true")
@ActiveProfiles("rls-sweep")
class RlsLeakSweepIT {

    static final String DB = TestPostgres.createDatabase();
    static final SweepFixtures FIXTURES = new SweepFixtures(new JdbcTemplate(TestPostgres.superuser(DB)));

    /** Path templates that legitimately answer other organisations' ids, each with its reason. None yet. */
    static final Map<String, String> EXEMPT = Map.of();

    /** Tables with an id that carry no tenant data to sweep, each with its reason. None yet. */
    static final Map<String, String> EXEMPT_TABLES = Map.of();

    /**
     * Typed fixtures for templates with several path parameters, each slot filled with an id of its kind. All three mixes of
     * "mine" and "foreign" are tried: both foreign, my own parent with a foreign child (an endpoint that authorises the
     * parent and trusts the child id), and a foreign parent with my own child.
     */
    static Map<String, List<Map<String, UUID>>> typedFixtures() {
        return Map.of("/api/sweep/classes/{classId}/members/{userId}", List.of(
                Map.of("classId", FIXTURES.classB, "userId", FIXTURES.userB),
                Map.of("classId", FIXTURES.classA, "userId", FIXTURES.userB),
                Map.of("classId", FIXTURES.classB, "userId", FIXTURES.userA)));
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired
    TestRestTemplate http;

    @BeforeAll
    static void seed(@Autowired JdbcTemplate migrated) {
        FIXTURES.seed(); // after the app's Flyway ran
    }

    private int getAsOrgA(String path) {
        HttpHeaders h = new HttpHeaders();
        // the real request path: a signed token for A's member and the real X-Org-Context header (no test filter)
        h.add("X-Org-Context", FIXTURES.orgA.toString());
        h.setBearerAuth(TestAuth.accessToken(FIXTURES.userA));
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode().value();
    }

    @Test
    void positiveControlOwnOrgRowIsServed() {
        assertEquals(200, getAsOrgA("/api/sweep/classes/" + FIXTURES.classA), "the endpoint works for the caller's own org");
    }

    @Test
    void everyGetEndpointRejectsForeignOrgIds() throws Exception {
        // a 404 for every foreign id means nothing unless the same endpoint answers 200 for the caller's own org
        assertEquals(200, getAsOrgA("/api/sweep/classes/" + FIXTURES.classA), "positive control");
        String openApi = http.getForObject("/v3/api-docs", String.class);
        assertEquals(200, getAsOrgA("/api/sweep/classes/" + FIXTURES.classA + "/members/" + FIXTURES.userA), "positive control, two parameters");
        assertEquals(200, getAsOrgA("/api/orgs/" + FIXTURES.orgA + "/role-grants"), "positive control: A's admin lists A's members");
        assertEquals(200, getAsOrgA("/api/orgs/" + FIXTURES.orgA + "/invitations"), "positive control: A's admin lists A's invitations");
        SweepFixtures.Catalog catalog = FIXTURES.foreignIds(EXEMPT_TABLES);
        assertEquals(List.of(), catalog.problems(), "every table with an id must have a foreign row or a reason");
        List<UUID> foreign = catalog.ids();
        assertTrue(foreign.contains(FIXTURES.classB) && foreign.size() >= 7, "foreign ids found by catalog: " + foreign);

        ForeignIdSweep.Result r = ForeignIdSweep.run(openApi, foreign, EXEMPT, typedFixtures(), (template, path) -> getAsOrgA(path));

        assertTrue(r.visited().contains("/api/sweep/classes/{id}"), "the sweep must reach the test endpoint, visited " + r.visited());
        assertTrue(r.visited().contains("/api/sweep/classes/{classId}/members/{userId}"), "multi-parameter endpoint swept: " + r.visited());
        assertTrue(r.visited().containsAll(List.of("/api/orgs/{id}/role-grants", "/api/orgs/{id}/invitations")),
                "the org endpoints of P1.5 are swept through the real X-Org-Context binding: " + r.visited());
        assertFalse(r.visited().isEmpty(), "a sweep that visits nothing proves nothing");
        assertEquals(List.of(), r.unsweepable());
        assertEquals(List.of(), r.leaks());
    }
}
