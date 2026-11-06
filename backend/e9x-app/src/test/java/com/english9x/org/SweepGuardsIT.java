package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.sweep.ForeignIdSweep;
import com.english9x.org.sweep.SweepFixtures;
import com.english9x.support.TestPostgres;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The sweep must not be passable by omission. Each failure mode a later task could fall into is provoked here:
 * a new table nobody seeded, a table nobody classified, and a multi-parameter endpoint nobody gave a typed fixture.
 */
class SweepGuardsIT {

    static final String DB = TestPostgres.createDatabase();
    static JdbcTemplate su;
    static SweepFixtures fixtures;

    @BeforeAll
    static void migrateAndSeed() {
        Flyway.configure().dataSource(TestPostgres.jdbcUrl(DB), TestPostgres.OWNER, TestPostgres.OWNER_PASSWORD)
                .locations("classpath:db/migration").load().migrate();
        su = new JdbcTemplate(TestPostgres.superuser(DB));
        fixtures = new SweepFixtures(su);
        fixtures.seed();
    }

    @Test
    void baselineSeedHasNoProblems() {
        assertEquals(List.of(), fixtures.foreignIds(Map.of()).problems());
    }

    @Test
    void aNewTenantTableWithoutAForeignRowFailsTheSweep() {
        su.execute("create table orders_demo (id uuid primary key default gen_random_uuid(), owner_org_id uuid)");
        try {
            List<String> problems = fixtures.foreignIds(Map.of()).problems();
            assertEquals(1, problems.size(), problems.toString());
            assertTrue(problems.get(0).contains("orders_demo") && problems.get(0).contains("no row belonging to org B"), problems.get(0));

            su.update("insert into orders_demo(owner_org_id) values (?)", fixtures.orgB);
            assertEquals(List.of(), fixtures.foreignIds(Map.of()).problems(), "seeding a foreign row clears it");
            assertTrue(fixtures.foreignIds(Map.of()).ids().size() > 7);
        } finally {
            su.execute("drop table orders_demo");
        }
    }

    @Test
    void aTableThatIsNeitherClassifiedNorExemptFailsTheSweep() {
        su.execute("create table mystery_demo (id uuid primary key default gen_random_uuid(), note text)");
        try {
            List<String> problems = fixtures.foreignIds(Map.of()).problems();
            assertEquals(1, problems.size(), problems.toString());
            assertTrue(problems.get(0).contains("mystery_demo") && problems.get(0).contains("classify it"), problems.get(0));
            assertEquals(List.of(), fixtures.foreignIds(Map.of("mystery_demo", "public lookup data")).problems(), "a written reason exempts it");
        } finally {
            su.execute("drop table mystery_demo");
        }
    }

    private static final String TWO_PARAMS = """
            {"paths":{"/api/classes/{classId}/students/{studentId}":{"get":{"parameters":[
              {"name":"classId","in":"path","schema":{"type":"string","format":"uuid"}},
              {"name":"studentId","in":"path","schema":{"type":"string","format":"uuid"}}]}}}}""";

    @Test
    void aMultiParameterEndpointFailsUntilItHasATypedFixtureOrAnExemption() throws Exception {
        UUID classB = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        String template = "/api/classes/{classId}/students/{studentId}";

        ForeignIdSweep.Result bare = ForeignIdSweep.run(TWO_PARAMS, List.of(classB), Map.of(), Map.of(), (t, p) -> 404);
        assertEquals(1, bare.unsweepable().size(), "answering 404 to a repeated id proves nothing, so it must fail");
        assertTrue(bare.visited().isEmpty());

        ForeignIdSweep.Result exempt = ForeignIdSweep.run(TWO_PARAMS, List.of(classB), Map.of(template, "reason"), Map.of(), (t, p) -> 404);
        assertEquals(List.of(), exempt.unsweepable());

        Map<String, List<Map<String, UUID>>> typed = Map.of(template, List.of(Map.of("classId", classB, "studentId", userB)));
        List<String> paths = new java.util.ArrayList<>();
        ForeignIdSweep.Result ok = ForeignIdSweep.run(TWO_PARAMS, List.of(classB), Map.of(), typed, (t, p) -> { paths.add(p); return 404; });
        assertEquals(List.of(), ok.unsweepable());
        assertEquals(List.of("/api/classes/" + classB + "/students/" + userB), paths, "each slot gets the id of its own kind");

        ForeignIdSweep.Result leaky = ForeignIdSweep.run(TWO_PARAMS, List.of(classB), Map.of(), typed, (t, p) -> 200);
        assertEquals(1, leaky.leaks().size());

        ForeignIdSweep.Result partial = ForeignIdSweep.run(TWO_PARAMS, List.of(classB), Map.of(),
                Map.of(template, List.of(Map.of("classId", classB))), (t, p) -> 404);
        assertEquals(1, partial.unsweepable().size(), "a typed fixture must cover every parameter");
    }
}
