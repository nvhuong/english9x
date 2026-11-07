package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** GET/PUT /api/orgs/{id}/role-grants. Every write goes through the real policies; grants never exist without a membership. */
class RoleGrantsIT extends OrgIT {

    private Res put(UUID org, UUID target, List<Map<String, Object>> roles, UUID asUser, UUID inOrg) {
        return call(HttpMethod.PUT, "/api/orgs/" + org + "/role-grants", Map.of("userId", target.toString(), "roles", roles), asUser, inOrg);
    }

    @Test
    void anOrgAdminListsTheMembersOfTheirOrganisationWithTheirRoles() {
        UUID a = org("CENTER");
        UUID admin = orgAdmin(a);
        UUID teacher = user("teacher");
        grant(teacher, a, "IELTS", "TEACHER");
        UUID learner = user("learner");
        member(a, learner);
        UUID b = org("CENTER");
        UUID bMember = user("bmember");
        grant(bMember, b, null, "ORG_ADMIN");

        Res r = get("/api/orgs/" + a + "/role-grants", admin, a);
        assertEquals(200, r.status(), r.raw());
        JsonNode members = r.body().path("members");
        assertEquals(3, members.size(), r.raw());
        assertTrue(r.raw().contains(emailOf(teacher)) && r.raw().contains("TEACHER") && r.raw().contains(emailOf(learner)));
        assertFalse(r.raw().contains(emailOf(bMember)), "another organisation's people are not listed");
    }

    @Test
    void aSysAdminManagesAnyOrganisationFromTheEnglish9xContext() {
        UUID a = org("CENTER");
        UUID admin = orgAdmin(a);
        UUID s = sysAdmin();
        assertEquals(200, get("/api/orgs/" + a + "/role-grants", s, null).status());
        UUID learner = user("learner");
        member(a, learner);
        Res r = put(a, learner, List.of(role("TEACHER", "IELTS")), s, null);
        assertEquals(200, r.status(), r.raw());
        assertEquals(List.of("TEACHER:IELTS:" + a), grantsOf(learner));
        assertEquals(404, get("/api/orgs/" + UUID.randomUUID() + "/role-grants", s, null).status());
        assertTrue(isMember(a, admin));
    }

    @Test
    void putReplacesTheGrantsOfOneMemberInThatOrganisationOnly() {
        UUID a = org("CENTER");
        UUID b = org("CENTER");
        UUID admin = orgAdmin(a);
        UUID m = user("multi");
        grant(m, a, "IELTS", "TEACHER");
        grant(m, a, null, "LEARNER");
        grant(m, b, "TOEIC", "TEACHER");
        grant(m, null, "IELTS", "AUTHOR");
        Res r = put(a, m, List.of(role("REVIEWER", "TOEIC"), role("REVIEWER", "TOEIC")), admin, a);
        assertEquals(200, r.status(), r.raw());
        assertEquals(List.of("AUTHOR:IELTS:-", "REVIEWER:TOEIC:" + a, "TEACHER:TOEIC:" + b),
                grantsOf(m), "A's grants replaced (duplicates collapsed); B's and English9x's untouched");
        Res none = put(a, m, List.of(), admin, a);
        assertEquals(200, none.status(), none.raw());
        assertTrue(isMember(a, m), "removing the roles leaves the membership");
    }

    @Test
    void aNonMemberCannotBeGrantedAnything() {
        UUID a = org("CENTER");
        UUID admin = orgAdmin(a);
        UUID outsider = user("outsider");
        Res r = put(a, outsider, List.of(role("ORG_ADMIN", null)), admin, a);
        assertEquals(404, r.status(), r.raw());
        assertEquals(List.of(), grantsOf(outsider));
        assertFalse(isMember(a, outsider), "no path makes a grant without a membership; invitations add both");
        assertEquals(404, put(a, UUID.randomUUID(), List.of(role("ORG_ADMIN", null)), admin, a).status());
    }

    @Test
    void anOrgAdminOfAnotherOrganisationOrAPlainMemberIsRefused() {
        UUID a = org("CENTER");
        UUID b = org("CENTER");
        UUID adminB = orgAdmin(b);
        UUID target = user("target");
        member(a, target);
        assertEquals(404, put(a, target, List.of(role("TEACHER", "IELTS")), adminB, b).status(), "ctx B, path A");
        assertEquals(403, put(a, target, List.of(role("TEACHER", "IELTS")), adminB, null).status(), "no context");
        assertEquals(403, get("/api/orgs/" + a + "/role-grants", adminB, null).status());
        assertEquals(404, get("/api/orgs/" + a + "/role-grants", adminB, b).status());
        UUID teacher = user("teacher");
        grant(teacher, a, "IELTS", "TEACHER");
        assertEquals(403, put(a, target, List.of(role("TEACHER", "IELTS")), teacher, a).status(), "a teacher is not an admin");
        assertEquals(403, get("/api/orgs/" + a + "/role-grants", teacher, a).status());
        assertEquals(List.of(), grantsOf(target));
        assertEquals(401, call(HttpMethod.GET, "/api/orgs/" + a + "/role-grants", null, (String) null, Map.of()).status());
    }

    @Test
    void nobodyEscalatesThroughTheRoleGrantsEndpoint() {
        UUID a = org("CENTER");
        UUID admin = orgAdmin(a);
        UUID target = user("t");
        member(a, target);
        // SYS_ADMIN is English9x only, an organisation can never mint one
        Res r = put(a, target, List.of(role("SYS_ADMIN", null)), admin, a);
        assertEquals(400, r.status(), r.raw());
        assertEquals(List.of(), grantsOf(target));
        // an exam-scoped role needs its exam
        assertEquals(400, put(a, target, List.of(role("TEACHER", null)), admin, a).status());
        assertEquals(400, put(a, target, List.of(role("TEACHER", "KLINGON")), admin, a).status());
        assertEquals(List.of(), grantsOf(target));
    }

    @Test
    void anOrganisationKeepsAtLeastOneAdmin() {
        UUID a = org("CENTER");
        UUID only = orgAdmin(a);
        Res r = put(a, only, List.of(role("LEARNER", null)), only, a);
        assertEquals(409, r.status(), r.raw());
        assertEquals("urn:e9x:problem:last-org-admin", r.problemType());
        assertEquals(List.of("ORG_ADMIN:-:" + a), grantsOf(only));
        UUID second = user("second");
        member(a, second);
        assertEquals(200, put(a, second, List.of(role("ORG_ADMIN", null)), only, a).status());
        assertEquals(200, put(a, only, List.of(role("LEARNER", null)), only, a).status(), "now there is another admin");
        // the former admin lost the power with the grant
        assertEquals(403, get("/api/orgs/" + a + "/role-grants", only, a).status());
    }

    @Test
    void theMembershipCannotBeFoundMissingWhenAnAdminGrantExists() {
        // every organisation grant in the database has a membership row, whatever path wrote it
        UUID a = org("CENTER");
        UUID admin = orgAdmin(a);
        UUID m = user("m");
        member(a, m);
        Res written = put(a, m, List.of(role("ORG_ADMIN", null), role("TEACHER", "IELTS")), admin, a);
        assertEquals(200, written.status(), written.raw());
        assertEquals(List.of("ORG_ADMIN:-:" + a, "TEACHER:IELTS:" + a), grantsOf(m), "the write the next check depends on really happened");
        assertEquals(0, su.queryForObject("""
                select count(*) from role_grants g where g.org_id is not null
                   and not exists (select 1 from org_memberships m where m.org_id = g.org_id and m.user_id = g.user_id)""", Integer.class));
    }

    // ---- concurrency around the last-admin rule: the requests are made to wait at the organisation's lock, which this test holds ------

    private void awaitWaiters(int n) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = su.queryForObject("""
                    select count(*) from pg_locks where locktype = 'advisory' and not granted
                       and database = (select oid from pg_database where datname = current_database())""", Integer.class);
            if (waiting != null && waiting >= n) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(n + " requests never reached the organisation lock");
    }

    /** Runs the calls with the org lock held, waits until they are all queued at it, does {@code meanwhile} in the same commit, lets go. */
    private List<Res> atTheLock(UUID org, java.util.function.Consumer<java.sql.Connection> meanwhile, List<java.util.concurrent.Callable<Res>> calls) throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(calls.size());
        try (java.sql.Connection c = com.english9x.support.TestPostgres.superuser(DB).getConnection()) {
            c.setAutoCommit(false);
            try (java.sql.PreparedStatement lock = c.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 7))")) {
                lock.setString(1, org.toString());
                lock.execute();
            }
            List<java.util.concurrent.Future<Res>> running = new java.util.ArrayList<>();
            for (var call : calls) {
                running.add(pool.submit(call));
            }
            awaitWaiters(calls.size());
            meanwhile.accept(c);
            c.commit();
            List<Res> out = new java.util.ArrayList<>();
            for (var f : running) {
                out.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void sql(java.sql.Connection c, String statement, Object... args) {
        try (java.sql.PreparedStatement p = c.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) {
                p.setObject(i + 1, args[i]);
            }
            p.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void twoAdminsDemotingEachOtherGetOneSuccessAndOneLastAdminRefusal() throws Exception {
        UUID a = org("CENTER");
        UUID x = orgAdmin(a);
        UUID y = orgAdmin(a);
        List<Res> results = atTheLock(a, c -> { }, List.of(
                () -> put(a, y, List.of(role("LEARNER", null)), x, a),
                () -> put(a, x, List.of(role("LEARNER", null)), y, a)));
        List<Integer> statuses = results.stream().map(Res::status).sorted().toList();
        assertEquals(List.of(200, 409), statuses, results.toString());
        assertEquals("urn:e9x:problem:last-org-admin", results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().problemType());
        assertEquals(1, su.queryForObject("select count(*) from role_grants where org_id = ? and role = 'ORG_ADMIN'", Integer.class, a), "one admin is left");
    }

    @Test
    void anAdminDemotedWhileWaitingIsRefusedCleanlyAndWritesNothing() throws Exception {
        UUID a = org("CENTER");
        UUID keeps = orgAdmin(a);
        UUID demoted = orgAdmin(a);
        UUID target = user("target");
        grant(target, a, "IELTS", "TEACHER");
        // an edit with an insert and a delete: the insert would be an RLS error (500), the delete a silent 0 rows (200)
        List<Res> both = atTheLock(a, c -> sql(c, "delete from role_grants where user_id = ? and role = 'ORG_ADMIN'", demoted),
                List.of(() -> put(a, target, List.of(role("REVIEWER", "IELTS")), demoted, a)));
        assertEquals(403, both.get(0).status(), both.get(0).raw());
        assertEquals("urn:e9x:problem:forbidden", both.get(0).problemType());
        assertEquals(List.of("TEACHER:IELTS:" + a), grantsOf(target), "nothing was written");
        // only a delete
        grant(demoted, a, null, "ORG_ADMIN");
        List<Res> del = atTheLock(a, c -> sql(c, "delete from role_grants where user_id = ? and role = 'ORG_ADMIN'", demoted),
                List.of(() -> put(a, target, List.of(), demoted, a)));
        assertEquals(403, del.get(0).status(), "never a 0-row 200: " + del.get(0).raw());
        assertEquals(List.of("TEACHER:IELTS:" + a), grantsOf(target));
        assertTrue(isMember(a, keeps));
    }

    @Test
    void aSysAdminWhoLostTheGrantWhileWaitingIsRefusedCleanly() throws Exception {
        UUID a = org("CENTER");
        orgAdmin(a);
        UUID sys = sysAdmin();
        UUID target = user("target");
        grant(target, a, "IELTS", "TEACHER");
        List<Res> r = atTheLock(a, c -> sql(c, "delete from role_grants where user_id = ? and role = 'SYS_ADMIN'", sys),
                List.of(() -> put(a, target, List.of(role("REVIEWER", "IELTS")), sys, null)));
        assertEquals(403, r.get(0).status(), r.get(0).raw());
        assertEquals(List.of("TEACHER:IELTS:" + a), grantsOf(target));
    }
}
