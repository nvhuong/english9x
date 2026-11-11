package com.english9x.org.members;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.OrgIT;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** F44: join codes. Only the digest is stored, the code is shown once, redeeming is atomic and audited. */
class JoinCodeIT extends OrgIT {

    private record Code(UUID org, UUID admin, UUID id, String code) {}

    private Code code(int maxUses, UUID classId) {
        UUID org = org("CENTER");
        UUID admin = orgAdmin(org);
        return codeIn(org, admin, maxUses, classId);
    }

    private Code codeIn(UUID org, UUID admin, int maxUses, UUID classId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("classId", classId);
        body.put("expiresAt", Instant.now().plus(7, ChronoUnit.DAYS).toString());
        body.put("maxUses", maxUses);
        Res r = post("/api/orgs/" + org + "/join-codes", body, admin, org);
        assertEquals(201, r.status(), r.raw());
        return new Code(org, admin, UUID.fromString(r.body().path("joinCode").path("id").asText()), r.body().path("code").asText());
    }

    private Res join(String code, UUID user) {
        return post("/api/join/" + code, null, user, null);
    }

    @Test
    void createShowsTheCodeOnceAndStoresOnlyItsDigest() {
        Code c = code(5, null);
        assertEquals(8, c.code().length());
        assertEquals(1, su.queryForObject("select count(*) from join_codes where id = ? and code_hash = sha256(convert_to(?, 'UTF8'))", Integer.class, c.id(), c.code()));
        assertEquals(0, su.queryForObject("select count(*) from join_codes where code_hash = convert_to(?, 'UTF8')", Integer.class, c.code()));
        Res list = get("/api/orgs/" + c.org() + "/join-codes", c.admin(), c.org());
        assertEquals(200, list.status(), list.raw());
        assertFalse(list.raw().contains(c.code()), "a listing never shows the code");
        assertEquals("ACTIVE", list.body().get(0).path("status").asText());
        String detail = su.queryForObject("select detail::text from audit_log where action = 'join-code.create' and target_id = ?", String.class, c.id().toString());
        assertFalse(detail.contains(c.code()), detail);
        assertTrue(detail.contains("maxUses"), detail);
        // not for a learner, not across organisations
        UUID learner = user("learner");
        member(c.org(), learner);
        assertEquals(403, post("/api/orgs/" + c.org() + "/join-codes", Map.of("expiresAt", Instant.now().plusSeconds(3600).toString(), "maxUses", 1), learner, c.org()).status());
        UUID otherOrg = org("CENTER");
        assertEquals(404, get("/api/orgs/" + otherOrg + "/join-codes", c.admin(), c.org()).status());
    }

    @Test
    void joinMakesALearnerPutsThemInTheClassAndIsAudited() {
        UUID org = org("CENTER");
        UUID admin = orgAdmin(org);
        UUID klass = UUID.randomUUID();
        su.update("insert into classes(id,owner_org_id,name) values (?,?,?)", klass, org, "Lop A");
        Code c = codeIn(org, admin, 3, klass);
        UUID person = user("joiner");
        Res ok = join(c.code().toLowerCase().substring(0, 4) + "-" + c.code().substring(4), person); // case and a dash do not matter
        assertEquals(200, ok.status(), ok.raw());
        assertEquals("joined", ok.body().path("status").asText());
        assertTrue(isMember(org, person));
        assertEquals(List.of("LEARNER:-:" + org), grantsOf(person));
        assertEquals(1, su.queryForObject("select count(*) from class_members where class_id = ? and user_id = ?", Integer.class, klass, person));
        assertEquals(1, su.queryForObject("select used_count from join_codes where id = ?", Integer.class, c.id()));
        String detail = su.queryForObject("select detail::text from audit_log where action = 'org.join-code-redeem' and target_id = ? and actor_id = ?", String.class, org.toString(), person);
        assertTrue(detail.contains(klass.toString()), detail);
        assertFalse(detail.contains(c.code()), "not the code: " + detail);
        assertFalse(detail.toLowerCase().contains("hash"), detail);
        // again: already a member, no use consumed, no second audit record
        Res again = join(c.code(), person);
        assertEquals(200, again.status());
        assertEquals("already-member", again.body().path("status").asText());
        assertEquals(1, su.queryForObject("select used_count from join_codes where id = ?", Integer.class, c.id()));
        assertEquals(1, su.queryForObject("select count(*) from audit_log where action = 'org.join-code-redeem' and actor_id = ?", Integer.class, person));
    }

    @Test
    void lastUseRaceAllowsOnlyOne() throws Exception {
        Code c = code(1, null);
        int n = 6;
        List<UUID> people = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            people.add(user("racer"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Res>> results = new java.util.ArrayList<>();
        for (UUID p : people) {
            results.add(pool.submit(() -> {
                go.await();
                return join(c.code(), p);
            }));
        }
        go.countDown();
        int joined = 0;
        int gone = 0;
        for (Future<Res> f : results) {
            Res r = f.get(60, TimeUnit.SECONDS);
            if (r.status() == 200) {
                joined++;
            } else {
                assertEquals(410, r.status(), r.raw());
                gone++;
            }
        }
        pool.shutdown();
        assertEquals(1, joined, "exactly one JOINED for the last use");
        assertEquals(n - 1, gone);
        assertEquals(1, su.queryForObject("select used_count from join_codes where id = ?", Integer.class, c.id()));
        assertEquals(1, su.queryForObject("select count(*) from org_memberships where org_id = ?", Integer.class, c.org()) - 1, "the admin plus one joiner");
    }

    @Test
    void expiredRejected() {
        Code c = code(5, null);
        su.update("update join_codes set expires_at = now() - interval '1 second' where id = ?", c.id());
        UUID person = user("late");
        Res r = join(c.code(), person);
        assertEquals(410, r.status(), r.raw());
        assertEquals("urn:e9x:problem:join-code-invalid", r.problemType());
        assertFalse(isMember(c.org(), person));
        assertEquals(0, su.queryForObject("select used_count from join_codes where id = ?", Integer.class, c.id()));
        assertEquals("EXPIRED", get("/api/orgs/" + c.org() + "/join-codes", c.admin(), c.org()).body().get(0).path("status").asText());
    }

    @Test
    void exhaustedRevokedUnknownAndMalformedAreAllGone() {
        Code c = code(1, null);
        assertEquals(200, join(c.code(), user("first")).status());
        UUID second = user("second");
        assertEquals(410, join(c.code(), second).status(), "used up");
        Code r = code(5, null);
        assertEquals(204, call(HttpMethod.DELETE, "/api/orgs/" + r.org() + "/join-codes/" + r.id(), null, r.admin(), r.org()).status());
        assertEquals(410, join(r.code(), second).status(), "revoked");
        assertEquals(1, su.queryForObject("select count(*) from audit_log where action = 'join-code.revoke' and target_id = ?", Integer.class, r.id().toString()));
        assertEquals(410, join("ABCDEFGH", second).status(), "unknown");
        assertEquals(410, join("short", second).status(), "malformed");
    }

    @Test
    void anUnverifiedAccountIsAskedToVerify() {
        Code c = code(5, null);
        UUID person = user(email("unverified"), false);
        Res r = join(c.code(), person);
        assertEquals(403, r.status(), r.raw());
        assertEquals("urn:e9x:problem:email-not-verified", r.problemType());
        assertFalse(isMember(c.org(), person));
    }

    @Test
    void classOfAnotherOrganisationIsRefused() {
        UUID a = org("CENTER");
        UUID b = org("CENTER");
        UUID admin = orgAdmin(a);
        UUID foreign = UUID.randomUUID();
        su.update("insert into classes(id,owner_org_id,name) values (?,?,?)", foreign, b, "Other");
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("classId", foreign);
        body.put("expiresAt", Instant.now().plusSeconds(3600).toString());
        body.put("maxUses", 2);
        assertEquals(400, post("/api/orgs/" + a + "/join-codes", body, admin, a).status());
    }
}
