package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.sweep.ForeignIdSweep;
import com.english9x.org.sweep.SweepFixtures;
import com.english9x.support.TestAuth;
import com.english9x.support.TestPostgres;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/** Proves the sweep is not vacuous: with a read and a write that go past RLS, and a write nobody wrote a fixture for, it must report exactly those. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "springdoc.api-docs.enabled=true")
@ActiveProfiles({"rls-sweep", "rls-sweep-leak"})
class RlsLeakSweepDetectsLeakIT {

    static final String DB = TestPostgres.createDatabase();
    static final SweepFixtures FIXTURES = new SweepFixtures(new JdbcTemplate(TestPostgres.superuser(DB)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
        registry.add("e9x.test.leaky-url", () -> TestPostgres.jdbcUrl(DB));
        registry.add("e9x.test.leaky-user", () -> TestPostgres.container().getUsername());
        registry.add("e9x.test.leaky-password", () -> TestPostgres.container().getPassword());
    }

    @Autowired
    TestRestTemplate http;

    @BeforeAll
    static void seed(@Autowired JdbcTemplate migrated) {
        FIXTURES.seed();
    }

    @Test
    void sweepFailsOnAnUnscopedReadAnUnscopedWriteAndAWriteWithoutAFixture() throws Exception {
        String openApi = http.getForObject("/v3/api-docs", String.class);
        Map<String, ForeignIdSweep.Fixture> fixtures = new HashMap<>(FIXTURES.writeFixtures());
        fixtures.put("GET /api/sweep/classes/{classId}/members/{userId}", ForeignIdSweep.Fixture.read(List.of(
                new ForeignIdSweep.Attempt(Map.of("classId", FIXTURES.classB.toString(), "userId", FIXTURES.userB.toString())))));
        fixtures.put("PATCH /api/sweep/classes/{id}", ForeignIdSweep.Fixture.write("{\"name\":\"renamed\"}", List.of(),
                ForeignIdSweep.Control.of(Map.of("id", FIXTURES.classA.toString()), null)));
        fixtures.put("PUT /api/sweep-leaky/classes/{id}", ForeignIdSweep.Fixture.write(null, List.of(),
                ForeignIdSweep.Control.of(Map.of("id", FIXTURES.classA.toString()), null)));

        ForeignIdSweep.Result r = ForeignIdSweep.run(openApi, FIXTURES.foreignIds(Map.of()).ids(), SweepFixtures.EXEMPT_OPERATIONS, fixtures, c -> {
            HttpHeaders h = new HttpHeaders();
            if (ForeignIdSweep.ADMIN.equals(c.caller())) {
                h.setBearerAuth(TestAuth.accessToken(FIXTURES.admin));
            } else {
                h.add("X-Org-Context", FIXTURES.orgA.toString());
                h.setBearerAuth(TestAuth.accessToken(FIXTURES.userA));
            }
            h.setContentType(MediaType.APPLICATION_JSON);
            return http.exchange(c.path(), HttpMethod.valueOf(c.method()), new HttpEntity<>(c.body(), h), String.class).getStatusCode().value();
        });

        assertTrue(r.visited().containsAll(List.of("GET /api/sweep/classes/{id}", "GET /api/sweep-leaky/classes/{id}", "PUT /api/sweep-leaky/classes/{id}")), r.visited().toString());
        assertEquals(2, r.leaks().stream().filter(l -> l.contains(FIXTURES.classB.toString())).count(), "the leaky read and the leaky write of class B: " + r.leaks());
        assertTrue(r.leaks().stream().anyMatch(l -> l.startsWith("GET /api/sweep-leaky/classes/{id} answered 200")), r.leaks().toString());
        assertTrue(r.leaks().stream().anyMatch(l -> l.startsWith("PUT /api/sweep-leaky/classes/{id} answered 200")), r.leaks().toString());
        assertTrue(r.leaks().stream().allMatch(l -> l.contains("/api/sweep-leaky/")), "the correct routes are not reported: " + r.leaks());
        assertEquals("renamed by a stranger", new JdbcTemplate(TestPostgres.superuser(DB)).queryForObject("select name from classes where id = ?", String.class, FIXTURES.classB),
                "the hole is real: org A's member changed org B's row and the sweep reported it");
        assertEquals(1, r.unsweepable().size(), r.unsweepable().toString());
        assertTrue(r.unsweepable().get(0).startsWith("POST /api/sweep-leaky/no-fixture/{id}"), r.unsweepable().get(0));
    }
}
