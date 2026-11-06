package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.sweep.ForeignIdSweep;
import com.english9x.org.sweep.SweepFixtures;
import com.english9x.support.TestPostgres;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Proves the sweep is not vacuous: with one endpoint that reads past RLS, it must report exactly that leak. */
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
    void sweepFailsWhenAnEndpointLeaks() throws Exception {
        String openApi = http.getForObject("/v3/api-docs", String.class);
        ForeignIdSweep.Result r = ForeignIdSweep.run(openApi, FIXTURES.foreignIds(), Map.of(), (template, path) -> {
            HttpHeaders h = new HttpHeaders();
            h.add("X-Test-User", FIXTURES.userA.toString());
            h.add("X-Test-Org", FIXTURES.orgA.toString());
            return http.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode().value();
        });

        assertTrue(r.visited().containsAll(List.of("/sweep/classes/{id}", "/sweep-leaky/classes/{id}")), r.visited().toString());
        assertEquals(1, r.leaks().size(), "exactly the leaky class row: " + r.leaks());
        assertTrue(r.leaks().get(0).contains("/sweep-leaky/classes/{id}"), r.leaks().get(0));
        assertTrue(r.leaks().get(0).contains(FIXTURES.classB.toString()), r.leaks().get(0));
    }
}
