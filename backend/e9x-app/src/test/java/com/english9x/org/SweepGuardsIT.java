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
 * a new table nobody seeded, a table nobody classified, a composite or text key, a multi-parameter endpoint nobody gave a typed
 * fixture, and a write endpoint with no fixture, body or positive control.
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

    @Test
    void aCompositeOrTextKeyedTableIsWalkedLikeAnyOther() {
        su.execute("create table composite_demo (user_id uuid, kind text, primary key (user_id, kind))");
        su.execute("create table coded_demo (code text primary key, owner_org_id uuid)");
        su.execute("create table keyless_demo (org_id uuid, amount int)");
        su.update("insert into keyless_demo(org_id, amount) values (?, 1)", fixtures.orgB);
        try {
            List<String> problems = fixtures.foreignIds(Map.of()).problems();
            assertEquals(3, problems.size(), problems.toString());
            assertTrue(problems.stream().anyMatch(p -> p.contains("composite_demo") && p.contains("no row belonging to org B")), problems.toString());
            assertTrue(problems.stream().anyMatch(p -> p.contains("coded_demo") && p.contains("no row belonging to org B")), problems.toString());
            assertTrue(problems.stream().anyMatch(p -> p.contains("keyless_demo") && p.contains("no uuid or text key")), problems.toString());

            su.update("insert into composite_demo(user_id, kind) values (?, 'a kind of b')", fixtures.userB);
            su.update("insert into coded_demo(code, owner_org_id) values ('CODE-OF-B', ?)", fixtures.orgB);
            SweepFixtures.Catalog seeded = fixtures.foreignIds(Map.of("keyless_demo", "aggregate only, no endpoint takes a key of it"));
            assertEquals(List.of(), seeded.problems());
            assertTrue(seeded.ids().contains(fixtures.userB));
            assertTrue(seeded.texts().containsAll(List.of("a kind of b", "CODE-OF-B", fixtures.courseCodeB)), seeded.texts().toString());
        } finally {
            su.execute("drop table composite_demo, coded_demo, keyless_demo");
        }
    }

    private static final String TWO_PARAMS = """
            {"paths":{"/api/classes/{classId}/students/{studentId}":{"get":{"parameters":[
              {"name":"classId","in":"path","schema":{"type":"string","format":"uuid"}},
              {"name":"studentId","in":"path","schema":{"type":"string","format":"uuid"}}]}}}}""";

    private static final String OP = "GET /api/classes/{classId}/students/{studentId}";

    @Test
    void aMultiParameterEndpointFailsUntilItHasATypedFixtureOrAnExemption() throws Exception {
        UUID classB = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        ForeignIdSweep.Result bare = ForeignIdSweep.run(TWO_PARAMS, List.of(classB), Map.of(), Map.of(), c -> 404);
        assertEquals(1, bare.unsweepable().size(), "answering 404 to a repeated id proves nothing, so it must fail");
        assertTrue(bare.visited().isEmpty());

        ForeignIdSweep.Result exempt = ForeignIdSweep.run(TWO_PARAMS, List.of(classB), Map.of(OP, "reason"), Map.of(), c -> 404);
        assertEquals(List.of(), exempt.unsweepable());
        ForeignIdSweep.Result noReason = ForeignIdSweep.run(TWO_PARAMS, List.of(classB), Map.of(OP, " "), Map.of(), c -> 404);
        assertEquals(1, noReason.unsweepable().size(), "an exemption without a reason is none");

        Map<String, ForeignIdSweep.Fixture> typed = Map.of(OP, ForeignIdSweep.Fixture.read(List.of(
                new ForeignIdSweep.Attempt(Map.of("classId", classB.toString(), "studentId", userB.toString())))));
        List<String> paths = new java.util.ArrayList<>();
        ForeignIdSweep.Result ok = ForeignIdSweep.run(TWO_PARAMS, List.of(classB), Map.of(), typed, c -> { paths.add(c.path()); return 404; });
        assertEquals(List.of(), ok.unsweepable());
        assertEquals(List.of("/api/classes/" + classB + "/students/" + userB), paths, "each slot gets the id of its own kind");

        ForeignIdSweep.Result leaky = ForeignIdSweep.run(TWO_PARAMS, List.of(classB), Map.of(), typed, c -> 200);
        assertEquals(1, leaky.leaks().size());

        ForeignIdSweep.Result partial = ForeignIdSweep.run(TWO_PARAMS, List.of(classB), Map.of(),
                Map.of(OP, ForeignIdSweep.Fixture.read(List.of(new ForeignIdSweep.Attempt(Map.of("classId", classB.toString()))))), c -> 404);
        assertEquals(1, partial.unsweepable().size(), "a typed fixture must cover every parameter");
    }

    private static final String WRITE = """
            {"paths":{"/api/things/{id}":{"put":{"requestBody":{"required":true},"parameters":[
              {"name":"id","in":"path","schema":{"type":"string","format":"uuid"}}]}}}}""";

    private static final String WRITE_OP = "PUT /api/things/{id}";

    @Test
    void aWriteEndpointFailsUntilItHasAFixtureWithABodyAndAPositiveControl() throws Exception {
        UUID b = UUID.randomUUID();
        ForeignIdSweep.Control own = ForeignIdSweep.Control.of(Map.of("id", "own"), null);

        assertTrue(ForeignIdSweep.run(WRITE, List.of(b), Map.of(), Map.of(), c -> 404).unsweepable().get(0).contains("needs a fixture"), "no fixture");
        assertTrue(ForeignIdSweep.run(WRITE, List.of(b), Map.of(), Map.of(WRITE_OP, ForeignIdSweep.Fixture.write(null, List.of(), own)), c -> 404)
                .unsweepable().get(0).contains("requires a body"), "a required body is missing");
        assertTrue(ForeignIdSweep.run(WRITE, List.of(b), Map.of(), Map.of(WRITE_OP, ForeignIdSweep.Fixture.write("{}", List.of(), null)), c -> 404)
                .unsweepable().get(0).contains("positive control"), "no control");

        Map<String, ForeignIdSweep.Fixture> good = Map.of(WRITE_OP, ForeignIdSweep.Fixture.write("{}", List.of(), own));
        assertTrue(ForeignIdSweep.run(WRITE, List.of(b), Map.of(), good, c -> 404).unsweepable().get(0).contains("the positive control answered 404"),
                "a route that refuses everybody proves nothing");

        List<ForeignIdSweep.Call> calls = new java.util.ArrayList<>();
        ForeignIdSweep.Result ok = ForeignIdSweep.run(WRITE, List.of(b), Map.of(), good, c -> {
            calls.add(c);
            return c.path().endsWith("own") ? 204 : 403;
        });
        assertEquals(List.of(), ok.unsweepable());
        assertEquals(List.of(), ok.leaks());
        assertEquals(List.of("/api/things/" + b, "/api/things/own"), calls.stream().map(ForeignIdSweep.Call::path).toList(), "the foreign id walked, the control last");
        assertTrue(calls.stream().allMatch(c -> c.method().equals("PUT") && "{}".equals(c.body())));

        assertEquals(1, ForeignIdSweep.run(WRITE, List.of(b), Map.of(), good, c -> c.path().endsWith("own") ? 204 : 400).leaks().size(),
                "a 400 before the tenant check is a finding: the body is valid");
        assertEquals(1, ForeignIdSweep.run(WRITE, List.of(b), Map.of(), good, c -> c.path().endsWith("own") ? 204 : 200).leaks().size());
        assertEquals(List.of(), ForeignIdSweep.run(WRITE, List.of(b), Map.of(WRITE_OP, "a written reason"), Map.of(), c -> 500).leaks());
    }

    @Test
    void aStringSlotCarriesATokenOrACode() throws Exception {
        String spec = """
                {"paths":{"/api/catalog/courses/{code}":{"get":{"parameters":[{"name":"code","in":"path","schema":{"type":"string"}}]}}}}""";
        String op = "GET /api/catalog/courses/{code}";
        assertEquals(1, ForeignIdSweep.run(spec, List.of(), Map.of(), Map.of(), c -> 404).unsweepable().size(), "not a uuid: it cannot be walked");
        List<String> paths = new java.util.ArrayList<>();
        ForeignIdSweep.Result r = ForeignIdSweep.run(spec, List.of(), Map.of(), Map.of(op, ForeignIdSweep.Fixture.read(
                List.of(new ForeignIdSweep.Attempt(Map.of("code", fixtures.courseCodeB))))), c -> { paths.add(c.path()); return 200; });
        assertEquals(List.of("/api/catalog/courses/" + fixtures.courseCodeB), paths);
        assertEquals(1, r.leaks().size(), "a private course by code, the F08 case, is swept instead of exempted");
    }
}
