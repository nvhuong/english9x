package com.english9x.org.members;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.OrgIT;
import com.english9x.support.SeatFixtures;
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

/** F44: leaving and removal take the seat back and keep the organisation's read-only history. */
class MemberRemovalIT extends OrgIT {

    private UUID course() {
        UUID id = UUID.randomUUID();
        su.update("insert into courses(id,owner_org_id,exam_code,code,title,status) values (?,null,'IELTS',?,'Course','PUBLISHED')", id, id.toString().replace("-", "").substring(0, 6).toUpperCase());
        return id;
    }

    private Res remove(UUID org, UUID user, UUID as, UUID inOrg) {
        return call(HttpMethod.DELETE, "/api/orgs/" + org + "/members/" + user, null, as, inOrg);
    }

    @Test
    void revokesSeatKeepsHistory() {
        UUID org = org("ENTERPRISE");
        UUID admin = orgAdmin(org);
        UUID learner = user("learner");
        grant(learner, org, null, "LEARNER");
        UUID klass = UUID.randomUUID();
        su.update("insert into classes(id,owner_org_id,name) values (?,?,?)", klass, org, "Lop A");
        su.update("insert into class_members(class_id,org_id,user_id) values (?,?,?)", klass, org, learner);
        UUID seat = SeatFixtures.seatEntitlement(su, org, learner, course(), Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(30, ChronoUnit.DAYS));
        UUID attempt = UUID.randomUUID();
        su.update("insert into exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,submitted_at,submit_reason,interrupted) "
                + "values (?,?,?,?,'SUBMITTED','IELTS',2,now(),'LEARNER',false)", attempt, learner, org, UUID.randomUUID());

        // a learner cannot remove somebody else; another organisation's admin cannot either
        UUID peer = user("peer");
        grant(peer, org, null, "LEARNER");
        assertEquals(403, remove(org, learner, peer, org).status());
        UUID otherOrg = org("CENTER");
        assertEquals(403, remove(org, learner, orgAdmin(otherOrg), otherOrg).status());
        assertTrue(isMember(org, learner));

        Res r = remove(org, learner, admin, org);
        assertEquals(204, r.status(), r.raw());
        assertFalse(isMember(org, learner));
        assertEquals(List.of(), grantsOf(learner));
        assertEquals(0, su.queryForObject("select count(*) from class_members where user_id = ?", Integer.class, learner));
        assertEquals(1, su.queryForObject("select count(*) from seat_assignments where id = ? and revoked_at is not null", Integer.class, seat), "the seat is taken back");
        assertEquals(0, su.queryForObject("select count(*) from entitlements where source_seat_id = ? and valid_until > now()", Integer.class, seat), "and what it granted ends");
        assertEquals(1, su.queryForObject("select count(*) from exam_attempts where id = ? and owner_org_id = ?", Integer.class, attempt, org), "history stays with the organisation");
        String detail = su.queryForObject("select detail::text from audit_log where action = 'member.remove' and target_id = ?", String.class, learner.toString());
        assertTrue(detail.contains(seat.toString()) && detail.contains("\"self\": false"), detail);
        assertEquals(404, remove(org, learner, admin, org).status(), "no longer a member");
    }

    @Test
    void aLearnerCanLeaveAndTheLastAdminCannot() {
        UUID org = org("CENTER");
        UUID admin = orgAdmin(org);
        UUID learner = user("leaver");
        grant(learner, org, null, "LEARNER");
        assertEquals(204, remove(org, learner, learner, org).status());
        assertFalse(isMember(org, learner));
        assertEquals(1, su.queryForObject("select count(*) from audit_log where action = 'member.remove' and actor_id = ? and detail->>'self' = 'true'", Integer.class, learner));

        Res last = remove(org, admin, admin, org);
        assertEquals(409, last.status(), last.raw());
        assertEquals("urn:e9x:problem:last-org-admin", last.problemType());
        UUID second = orgAdmin(org);
        assertEquals(204, remove(org, admin, second, org).status());
        assertEquals(409, remove(org, second, second, org).status());
    }

    /** Ledger 125: removing a member and replacing their roles are one queue per organisation; neither leaves a 500 or a grant without a membership. */
    @Test
    void removalRacingARoleGrantNeverLeavesAGrantWithoutMembership() throws Exception {
        for (int round = 0; round < 4; round++) {
            UUID org = org("CENTER");
            UUID admin = orgAdmin(org);
            UUID learner = user("racer");
            grant(learner, org, null, "LEARNER");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<Res> rm = pool.submit(() -> {
                go.await();
                return remove(org, learner, admin, org);
            });
            Future<Res> set = pool.submit(() -> {
                go.await();
                return call(HttpMethod.PUT, "/api/orgs/" + org + "/role-grants",
                        Map.of("userId", learner, "roles", List.of(role("LEARNER", null), role("TEACHER", "IELTS"))), admin, org);
            });
            go.countDown();
            Res a = rm.get(60, TimeUnit.SECONDS);
            Res b = set.get(60, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(204, a.status(), a.raw());
            assertTrue(b.status() == 200 || b.status() == 404, "role grant: " + b.status() + " " + b.raw());
            assertEquals(0, su.queryForObject("select count(*) from role_grants g where g.org_id = ? and not exists (select 1 from org_memberships m where m.org_id = g.org_id and m.user_id = g.user_id)", Integer.class, org));
        }
    }
}
