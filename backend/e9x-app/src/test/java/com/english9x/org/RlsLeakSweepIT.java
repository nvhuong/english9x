package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.sweep.ForeignIdSweep;
import com.english9x.org.sweep.SweepFixtures;
import com.english9x.org.sweep.SweepHttp;
import com.english9x.support.TestAuth;
import com.english9x.support.TestMinio;
import com.english9x.support.TestPostgres;
import java.util.HashMap;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Review Focus #1: a member of org A calls every endpoint, with every HTTP method, with ids of org B. The endpoint list comes
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

    /**
     * The test-only routes: reads with two parameters, and a write through a real PATCH with a body. All three mixes of "mine" and "foreign" are tried for two
     * parameters: both foreign, my own parent with a foreign child (an endpoint that authorises the parent and trusts the child id), and a foreign
     * parent with my own child.
     */
    static Map<String, ForeignIdSweep.Fixture> fixtures() {
        Map<String, ForeignIdSweep.Fixture> all = new HashMap<>(FIXTURES.writeFixtures());
        all.put("GET /api/sweep/classes/{classId}/members/{userId}", ForeignIdSweep.Fixture.read(List.of(
                new ForeignIdSweep.Attempt(Map.of("classId", FIXTURES.classB.toString(), "userId", FIXTURES.userB.toString())),
                new ForeignIdSweep.Attempt(Map.of("classId", FIXTURES.classA.toString(), "userId", FIXTURES.userB.toString())),
                new ForeignIdSweep.Attempt(Map.of("classId", FIXTURES.classB.toString(), "userId", FIXTURES.userA.toString())))));
        all.put("PATCH /api/sweep/classes/{id}", ForeignIdSweep.Fixture.write("{\"name\":\"renamed\"}", List.of(),
                ForeignIdSweep.Control.of(Map.of("id", FIXTURES.classA.toString()), null)));
        return all;
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
        TestMinio.configure(registry); // the media routes are real: the upload's positive control stores a file
    }

    @Autowired
    TestRestTemplate http;

    @BeforeAll
    static void seed(@Autowired JdbcTemplate migrated) {
        FIXTURES.seed(); // after the app's Flyway ran
    }

    private int getAsOrgA(String path) {
        return call(new ForeignIdSweep.Call("GET", path, path, null, null));
    }

    /** The real request path: a signed token and the real X-Org-Context header (no test filter); see {@link SweepHttp}. */
    private int call(ForeignIdSweep.Call c) {
        return SweepHttp.status(http, FIXTURES, c);
    }

    @Test
    void positiveControlOwnOrgRowIsServed() {
        assertEquals(200, getAsOrgA("/api/sweep/classes/" + FIXTURES.classA), "the endpoint works for the caller's own org");
    }

    @Test
    void everyEndpointWithEveryMethodRejectsForeignOrgIds() throws Exception {
        // a 404 for every foreign id means nothing unless the same endpoint answers 200 for the caller's own org
        assertEquals(200, getAsOrgA("/api/sweep/classes/" + FIXTURES.classA), "positive control");
        String openApi = http.getForObject("/v3/api-docs", String.class);
        assertEquals(200, getAsOrgA("/api/sweep/classes/" + FIXTURES.classA + "/members/" + FIXTURES.userA), "positive control, two parameters");
        assertEquals(200, getAsOrgA("/api/orgs/" + FIXTURES.orgA + "/role-grants"), "positive control: A's admin lists A's members");
        assertEquals(200, getAsOrgA("/api/orgs/" + FIXTURES.orgA + "/invitations"), "positive control: A's admin lists A's invitations");
        // P2.2: A's author gets a link for A's file (the sweep walks the url route with B's ids as A's ORG_ADMIN, who is no author: 404 for B's file and for A's own)
        assertEquals(200, call(new ForeignIdSweep.Call("GET", "/api/media/{id}/url", "/api/media/" + FIXTURES.mediaA + "/url", null, ForeignIdSweep.AUTHOR)),
                "positive control: A's author signs A's file");
        assertEquals(404, call(new ForeignIdSweep.Call("GET", "/api/media/{id}/url", "/api/media/" + FIXTURES.mediaB + "/url", null, ForeignIdSweep.AUTHOR)),
                "A's author does not sign B's file");
        assertEquals(200, getAsOrgA("/api/orders/" + FIXTURES.orderCodeA), "positive control: A's member reads A's member's order");
        SweepFixtures.Catalog catalog = FIXTURES.foreignIds(Map.of());
        assertEquals(List.of(), catalog.problems(), "every table must have a foreign row or a reason");
        List<UUID> foreign = catalog.ids();
        assertTrue(foreign.contains(FIXTURES.classB) && foreign.size() >= 7, "foreign ids found by catalog: " + foreign);
        assertTrue(catalog.texts().contains(FIXTURES.courseCodeB), "a text key is collected for a string slot: " + catalog.texts());
        String before = classNameOfB();

        ForeignIdSweep.Result r = ForeignIdSweep.run(openApi, foreign, SweepFixtures.EXEMPT_OPERATIONS, fixtures(), this::call);

        assertEquals(List.of(), r.unsweepable());
        assertEquals(List.of(), r.leaks());
        assertEquals(before, classNameOfB(), "nothing of org B changed");
        // every write route of the application was really called, and each answered 2xx to its own positive control (or it would be unsweepable)
        assertTrue(r.visited().containsAll(List.of("GET /api/sweep/classes/{id}", "GET /api/sweep/classes/{classId}/members/{userId}", "PATCH /api/sweep/classes/{id}",
                "GET /api/orgs/{id}/role-grants", "GET /api/orgs/{id}/invitations",
                "POST /api/me/notifications/{id}/read", "POST /api/orgs/{id}/invitations", "PUT /api/orgs/{id}/role-grants",
                "DELETE /api/orgs/{id}/invitations/{invitationId}", "DELETE /api/admin/invitations/{invitationId}",
                "POST /api/invitations/{token}/accept", "POST /api/admin/invitations", "POST /api/admin/organizations",
                "GET /api/media/{id}/url", "POST /api/cms/media",
                "POST /api/orders", "POST /api/admin/plans", "PUT /api/admin/plans/{id}", "GET /api/orders/{code}")),
                "visited " + r.visited());
    }

    private String classNameOfB() {
        return new JdbcTemplate(TestPostgres.superuser(DB)).queryForObject("select name from classes where id = ?", String.class, FIXTURES.classB);
    }
}
