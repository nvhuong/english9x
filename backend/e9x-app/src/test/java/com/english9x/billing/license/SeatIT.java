package com.english9x.billing.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.billing.BillingIT;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** F43: an organisation's ORG_ADMIN assigns seats to members and takes them back; the database keeps the capacity, the courses and the membership. */
class SeatIT extends BillingIT {

    @BeforeEach
    void realTime() {
        clock.set(Instant.now());
    }

    private record World(UUID admin, UUID org, UUID orgAdmin, UUID license, UUID c1, UUID c2, UUID c3) {}

    /** An organisation with an ORG_ADMIN and an ACTIVE licence of {@code seats} seats over two of three courses, created through the endpoint. */
    private World world(int seats) {
        UUID admin = sysAdmin();
        UUID org = org("ENTERPRISE");
        UUID orgAdmin = orgAdmin(org);
        UUID c1 = course("PUBLISHED");
        UUID c2 = course("PUBLISHED");
        UUID c3 = course("PUBLISHED");
        Map<String, Object> b = new java.util.HashMap<>(Map.of("orgId", org, "seats", seats, "months", 6, "courseIds", List.of(c1, c2), "priceVnd", 1_000_000, "activation", "CONTRACT"));
        b.put("contractNo", "HD-" + UUID.randomUUID().toString().substring(0, 6));
        Res r = post("/api/admin/org-licenses", b, admin, null);
        assertEquals(201, r.status(), r.raw());
        return new World(admin, org, orgAdmin, UUID.fromString(r.body().path("license").path("id").asText()), c1, c2, c3);
    }

    private UUID learner(World w, String label) {
        UUID u = user(label);
        member(w.org(), u);
        return u;
    }

    private Res assign(World w, UUID user, UUID... courses) {
        return post("/api/orgs/" + w.org() + "/licenses/" + w.license() + "/seats", Map.of("userId", user, "courseIds", List.of(courses)), w.orgAdmin(), w.org());
    }

    private Res revoke(World w, UUID user) {
        return call(HttpMethod.DELETE, "/api/orgs/" + w.org() + "/licenses/" + w.license() + "/seats/" + user, null, w.orgAdmin(), w.org());
    }

    private int live(World w) {
        return su.queryForObject("select count(*) from seat_assignments where license_id = ? and revoked_at is null", Integer.class, w.license());
    }

    @Test
    void assignGivesAccessUntilTheLicenseEnds() {
        World w = world(2);
        UUID u = learner(w, "seat");
        Res r = assign(w, u, w.c1(), w.c2());
        assertEquals(201, r.status(), r.raw());
        assertEquals(2, r.body().path("courseIds").size());
        Instant end = su.queryForObject("select valid_until from org_licenses where id = ?", java.sql.Timestamp.class, w.license()).toInstant();
        List<Map<String, Object>> ents = su.queryForList("select valid_until, source_org_id, source_order_id from entitlements where user_id = ?", u);
        assertEquals(2, ents.size());
        for (Map<String, Object> e : ents) {
            assertEquals(end.toEpochMilli(), ((java.sql.Timestamp) e.get("valid_until")).toInstant().toEpochMilli(), "ends with the licence");
            assertEquals(w.org(), e.get("source_org_id"));
        }
        Res mine = get("/api/me/entitlements", u, null);
        assertEquals(2, mine.body().size());
        assertTrue(mine.body().get(0).path("active").asBoolean());
        assertEquals(409, assign(w, u, w.c1()).status(), "one seat per person");
        // the audit record: seat id as target, the person and the courses, no email
        List<Map<String, Object>> log = su.queryForList("select target_id, detail::text d from audit_log where action = 'seat.assign' and detail->>'userId' = ?", u.toString());
        assertEquals(1, log.size());
        assertEquals("seat", su.queryForObject("select target_type from audit_log where action = 'seat.assign' and detail->>'userId' = ?", String.class, u.toString()));
        assertTrue(log.get(0).get("d").toString().contains(w.c1().toString()));
        assertFalse(log.get(0).get("d").toString().contains("@"));
        assertEquals(1, su.queryForObject("select count(*) from seat_assignments where id::text = ?", Integer.class, log.get(0).get("target_id")));
        // other people cannot assign: a learner, an administrator of another organisation
        UUID stranger = learner(w, "other");
        assertEquals(403, post("/api/orgs/" + w.org() + "/licenses/" + w.license() + "/seats", Map.of("userId", stranger, "courseIds", List.of(w.c1())), u, w.org()).status());
        UUID otherOrg = org("CENTER");
        assertEquals(404, post("/api/orgs/" + w.org() + "/licenses/" + w.license() + "/seats", Map.of("userId", stranger, "courseIds", List.of(w.c1())), orgAdmin(otherOrg), otherOrg).status());
        assertEquals(404, post("/api/orgs/" + otherOrg + "/licenses/" + w.license() + "/seats", Map.of("userId", stranger, "courseIds", List.of(w.c1())), orgAdmin(otherOrg), otherOrg).status(),
                "a licence of another organisation is not found");
    }

    @Test
    void cannotExceedSeats() throws Exception {
        World w = world(2);
        assertEquals(201, assign(w, learner(w, "first"), w.c1()).status());
        List<UUID> contenders = List.of(learner(w, "race-a"), learner(w, "race-b"), learner(w, "race-c"), learner(w, "race-d"));
        var pool = Executors.newFixedThreadPool(contenders.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (UUID c : contenders) {
            results.add(pool.submit(() -> {
                go.await();
                return assign(w, c, w.c1()).status();
            }));
        }
        go.countDown();
        int created = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int s = f.get();
            if (s == 201) {
                created++;
            } else if (s == 409) {
                conflict++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, created, "exactly one of the requests for the last seat succeeds");
        assertEquals(contenders.size() - 1, conflict);
        assertEquals(2, live(w));
        Res again = assign(w, learner(w, "late"), w.c1());
        assertEquals(409, again.status());
        assertEquals("urn:e9x:problem:no-seat-available", again.problemType());
        // the database refuses it by itself, whatever the service does: a seat row beyond the capacity (as the application role: backend/db-tests/44_org_licenses.sql)
        UUID late = learner(w, "direct");
        try {
            su.update("insert into seat_assignments(license_id,org_id,user_id,assigned_by) values (?,?,?,?)", w.license(), w.org(), late, w.orgAdmin());
            throw new AssertionError("the guard must refuse a third live seat");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            assertTrue(expected.getMostSpecificCause().getMessage().contains("seat capacity exceeded"));
        }
    }

    @Test
    void revokeEndsAccessAndFreesSeat() {
        World w = world(1);
        UUID a = learner(w, "holder");
        UUID b = learner(w, "waiting");
        assertEquals(201, assign(w, a, w.c1(), w.c2()).status());
        assertEquals(409, assign(w, b, w.c1()).status(), "the only seat is taken");
        assertTrue(get("/api/me/entitlements", a, null).body().get(0).path("active").asBoolean());

        clock.set(clock.instant().plusSeconds(10));   // the application clock stands still in tests: let time pass between the assignment and the revoke
        assertEquals(404, revoke(w, b).status(), "nothing to revoke");
        assertEquals(204, revoke(w, a).status());
        Res after = get("/api/me/entitlements", a, null);
        assertEquals(2, after.body().size());
        for (var e : after.body()) {
            assertFalse(e.path("active").asBoolean(), "access ended at once");
        }
        assertEquals(0, su.queryForObject("select count(*) from entitlements e join seat_assignments s on s.id = e.source_seat_id where e.user_id = ? and s.revoked_at is not null and e.valid_until > s.revoked_at", Integer.class, a));
        assertEquals(0, live(w));
        assertEquals(404, revoke(w, a).status(), "a second revoke finds no live seat");

        assertEquals(201, assign(w, b, w.c1()).status(), "the seat is free again");
        assertEquals(409, assign(w, learner(w, "third"), w.c1()).status());
        // the same person can hold a seat again after losing it
        assertEquals(204, revoke(w, b).status());
        assertEquals(201, assign(w, a, w.c2()).status());
        assertEquals(2, su.queryForObject("select count(*) from seat_assignments where user_id = ?", Integer.class, a));

        List<Map<String, Object>> log = su.queryForList("select target_type, detail::text d from audit_log where action = 'seat.revoke' and detail->>'userId' = ?", a.toString());
        assertEquals(1, log.size());
        assertEquals("seat", log.get(0).get("target_type"));
        assertTrue(log.get(0).get("d").toString().contains(w.c1().toString()));
        assertFalse(log.get(0).get("d").toString().contains("@"));
    }

    @Test
    void coursesOutsideLicenseRejected() {
        World w = world(3);
        UUID u = learner(w, "c");
        Res r = assign(w, u, w.c1(), w.c3());
        assertEquals(422, r.status(), r.raw());
        assertEquals("urn:e9x:problem:course-not-in-license", r.problemType());
        assertEquals(0, live(w));
        assertEquals(0, su.queryForObject("select count(*) from entitlements where user_id = ?", Integer.class, u));
        assertEquals(400, post("/api/orgs/" + w.org() + "/licenses/" + w.license() + "/seats", Map.of("userId", u, "courseIds", List.of()), w.orgAdmin(), w.org()).status());
        assertEquals(201, assign(w, u, w.c2()).status(), "a subset is fine");
        assertEquals(1, su.queryForObject("select count(*) from entitlements where user_id = ?", Integer.class, u));
    }

    @Test
    void memberOfOtherOrgRejected() {
        World w = world(3);
        UUID outsider = user("outsider");
        UUID other = org("CENTER");
        member(other, outsider);
        Res r = assign(w, outsider, w.c1());
        assertEquals(422, r.status(), r.raw());
        assertEquals("urn:e9x:problem:not-an-org-member", r.problemType());
        assertEquals(0, live(w));
        assertEquals(422, assign(w, UUID.randomUUID(), w.c1()).status(), "nobody at all is not a member either");
    }
}
