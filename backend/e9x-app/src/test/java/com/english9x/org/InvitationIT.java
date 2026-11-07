package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.SmtpSink;
import com.english9x.support.TestAuth;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
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

/** F04/F05 backend: invite by email, accept with the token from the mail, and exactly the recorded roles are granted. */
class InvitationIT extends OrgIT {

    private static List<Map<String, Object>> roles(Map<String, Object>... r) {
        return List.of(r);
    }

    @SuppressWarnings("unchecked")
    private Res inviteStaff(UUID asSysAdmin, String email, String role, String exam) {
        return post("/api/admin/invitations", Map.of("email", email, "roles", roles(role(role, exam))), asSysAdmin, null);
    }

    private String tokenFrom(SmtpSink.Mail mail) {
        assertTrue(mail.link().startsWith(TestAuth.BASE_URL + "/accept-invitation?token="), mail.link());
        return mail.linkParam("token");
    }

    private Res accept(String token, UUID asUser) {
        return post("/api/invitations/" + token + "/accept", null, asUser, null);
    }

    @Test
    void acceptGrantsRoles() {
        UUID sys = sysAdmin();
        String invitee = email("author");
        Res inv = inviteStaff(sys, invitee, "AUTHOR", "IELTS");
        assertEquals(201, inv.status(), inv.raw());
        assertFalse(inv.raw().contains("token"), "the token is only in the mail");

        SmtpSink.Mail mail = TestAuth.SMTP.await(invitee);
        assertTrue(mail.subject().contains("English9x"), mail.subject());
        String token = tokenFrom(mail);
        assertEquals(1, su.queryForObject("select count(*) from invitations where token_hash = sha256(convert_to(?, 'UTF8'))", Integer.class, token),
                "stored as the SHA-256 digest of the token");
        assertEquals(0, su.queryForObject("select count(*) from invitations where token_hash = convert_to(?, 'UTF8')", Integer.class, token),
                "never the token itself");

        UUID person = user(invitee, true);
        Res ok = accept(token, person);
        assertEquals(200, ok.status(), ok.raw());
        assertEquals(List.of("AUTHOR:IELTS:-"), grantsOf(person), "exactly the invited role, for exactly that exam");

        Res me = get("/api/me/context", person, null);
        assertTrue(me.raw().contains("\"AUTHOR\"") && me.raw().contains("IELTS"), me.raw());
        // single use
        Res again = accept(token, person);
        assertEquals(404, again.status(), again.raw());
        assertEquals("urn:e9x:problem:invitation-invalid", again.problemType());
        assertEquals(1, grantsOf(person).size());
    }

    @Test
    void anOrganisationInvitationAddsTheMembershipWithTheRole() {
        UUID s = sysAdmin();
        UUID a = org("CENTER");
        String invitee = email("orgadmin");
        Res inv = post("/api/orgs/" + a + "/invitations", Map.of("email", invitee, "roles", roles(role("ORG_ADMIN", null))), s, null);
        assertEquals(201, inv.status(), inv.raw());
        String token = tokenFrom(TestAuth.SMTP.await(invitee));
        UUID person = user(invitee, true);
        assertFalse(isMember(a, person));
        Res ok = accept(token, person);
        assertEquals(200, ok.status(), ok.raw());
        assertEquals(a.toString(), ok.body().path("orgId").asText());
        assertTrue(isMember(a, person), "the membership row comes with the grant");
        assertEquals(List.of("ORG_ADMIN:-:" + a), grantsOf(person));
        // and now they really are the admin of it
        assertEquals(200, get("/api/orgs/" + a + "/role-grants", person, a).status());
    }

    @Test
    void anOrgAdminInvitesIntoTheirOwnOrganisationOnly() {
        UUID a = org("CENTER");
        UUID b = org("CENTER");
        UUID admin = orgAdmin(a);
        String invitee = email("teacher");
        Res ok = post("/api/orgs/" + a + "/invitations", Map.of("email", invitee, "roles", roles(role("TEACHER", "IELTS"))), admin, a);
        assertEquals(201, ok.status(), ok.raw());
        Res foreign = post("/api/orgs/" + b + "/invitations", Map.of("email", invitee, "roles", roles(role("TEACHER", "IELTS"))), admin, a);
        assertEquals(404, foreign.status(), foreign.raw());
        Res inPersonal = post("/api/orgs/" + a + "/invitations", Map.of("email", invitee, "roles", roles(role("LEARNER", null))), admin, null);
        assertEquals(403, inPersonal.status(), "an org admin outside their organisation context has no power over it: " + inPersonal.raw());
        Res staff = post("/api/admin/invitations", Map.of("email", invitee, "roles", roles(role("AUTHOR", "IELTS"))), admin, a);
        assertEquals(403, staff.status(), "an org admin cannot invite English9x staff");
        UUID learner = user("plain");
        member(a, learner);
        assertEquals(403, post("/api/orgs/" + a + "/invitations", Map.of("email", invitee, "roles", roles(role("LEARNER", null))), learner, a).status());
    }

    @Test
    void aForgedExpiredRevokedOrReusedTokenIsTheSameNotFound() throws Exception {
        UUID s = sysAdmin();
        UUID person = user("holder");
        // forged: well-shaped but unknown, and garbage
        for (String bad : List.of("A".repeat(43), "x", "0".repeat(300))) {
            Res r = accept(bad, person);
            assertTrue(r.status() == 404 || r.status() == 400, bad + " -> " + r.status() + " " + r.raw());
        }
        assertEquals("urn:e9x:problem:invitation-invalid", accept("A".repeat(43), person).problemType());

        String expiring = email("exp");
        assertEquals(201, inviteStaff(s, expiring, "REVIEWER", "TOEIC").status());
        String tokenExp = tokenFrom(TestAuth.SMTP.await(expiring));
        su.update("update invitations set expires_at = now() - interval '1 second' where email = ?", expiring);
        UUID pExp = user(expiring, true);
        Res late = accept(tokenExp, pExp);
        assertEquals(404, late.status(), late.raw());
        assertEquals("urn:e9x:problem:invitation-invalid", late.problemType());
        assertEquals(List.of(), grantsOf(pExp));

        String revoked = email("rev");
        Res inv = inviteStaff(s, revoked, "REVIEWER", "TOEIC");
        String tokenRev = tokenFrom(TestAuth.SMTP.await(revoked));
        assertEquals(204, call(HttpMethod.DELETE, "/api/admin/invitations/" + inv.body().path("id").asText(), null, s, null).status());
        UUID pRev = user(revoked, true);
        assertEquals(404, accept(tokenRev, pRev).status());
        assertEquals(List.of(), grantsOf(pRev));

        assertEquals(401, post("/api/invitations/" + tokenRev + "/accept", null, null, null).status(), "accepting needs a signed-in account");
    }

    @Test
    void aDifferentSignedInEmailIsRefusedAndTheInvitationSurvives() {
        UUID s = sysAdmin();
        String invitee = email("real");
        assertEquals(201, inviteStaff(s, invitee, "AUTHOR", "TOEIC").status());
        String token = tokenFrom(TestAuth.SMTP.await(invitee));
        UUID other = user("forwarded");
        Res r = accept(token, other);
        assertEquals(403, r.status(), r.raw());
        assertEquals("urn:e9x:problem:invitation-email-mismatch", r.problemType());
        assertFalse(r.raw().contains(invitee), "the invited address is not disclosed to somebody else");
        assertEquals(List.of(), grantsOf(other));
        // the right person can still use it
        UUID person = user(invitee, true);
        assertEquals(200, accept(token, person).status());
        assertEquals(List.of("AUTHOR:TOEIC:-"), grantsOf(person));
    }

    @Test
    void theInvitedAddressIsMatchedCaseInsensitively() {
        UUID s = sysAdmin();
        String invitee = email("case");
        assertEquals(201, inviteStaff(s, invitee.toUpperCase(), "AUTHOR", "IELTS").status());
        String token = tokenFrom(TestAuth.SMTP.await(invitee));
        UUID person = user(invitee, true);
        assertEquals(200, accept(token, person).status());
    }

    @Test
    void anUnverifiedMailboxMustVerifyFirst() {
        UUID s = sysAdmin();
        String invitee = email("unverified");
        assertEquals(201, inviteStaff(s, invitee, "AUTHOR", "IELTS").status());
        String token = tokenFrom(TestAuth.SMTP.await(invitee));
        UUID squatter = user(invitee, false);
        Res r = accept(token, squatter);
        assertEquals(403, r.status(), r.raw());
        assertEquals("urn:e9x:problem:email-not-verified", r.problemType());
        assertEquals(List.of(), grantsOf(squatter));
        su.update("update users set email_verified_at = now() where id = ?", squatter);
        assertEquals(200, accept(token, squatter).status(), "after verifying, the same link works");
    }

    @Test
    void twoAcceptsAtOnceGrantOnce() throws Exception {
        UUID s = sysAdmin();
        UUID a = org("CENTER");
        String invitee = email("race");
        assertEquals(201, post("/api/orgs/" + a + "/invitations", Map.of("email", invitee, "roles", roles(role("LEARNER", null), role("TEACHER", "IELTS"))), s, null).status());
        String token = tokenFrom(TestAuth.SMTP.await(invitee));
        UUID person = user(invitee, true);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return accept(token, person).status();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(1, statuses.stream().filter(x -> x == 200).count(), "exactly one wins: " + statuses);
        assertEquals(3, statuses.stream().filter(x -> x == 404).count(), statuses.toString());
        assertEquals(2, grantsOf(person).size());
    }

    @Test
    void inviteResponsesDoNotRevealWhetherAnAccountExists() {
        UUID s = sysAdmin();
        String known = email("known");
        user(known, true);
        String unknown = email("unknown");
        Res a = inviteStaff(s, known, "AUTHOR", "IELTS");
        Res b = inviteStaff(s, unknown, "AUTHOR", "IELTS");
        assertEquals(a.status(), b.status());
        assertEquals(a.body().fieldNames().next(), b.body().fieldNames().next());
        assertEquals(sortedKeys(a.body()), sortedKeys(b.body()));
        assertNotNull(TestAuth.SMTP.await(known));
        assertNotNull(TestAuth.SMTP.await(unknown));
    }

    private static List<String> sortedKeys(JsonNode n) {
        List<String> k = new ArrayList<>();
        n.fieldNames().forEachRemaining(k::add);
        return k.stream().sorted().toList();
    }

    @Test
    void aNewInvitationRetiresAnOlderPendingOneForTheSameAddressAndOrganisation() {
        UUID s = sysAdmin();
        String invitee = email("twice");
        assertEquals(201, inviteStaff(s, invitee, "AUTHOR", "IELTS").status());
        String first = tokenFrom(TestAuth.SMTP.await(invitee));
        assertEquals(201, inviteStaff(s, invitee, "REVIEWER", "TOEIC").status());
        String second = tokenFrom(TestAuth.SMTP.await(invitee, 1));
        UUID person = user(invitee, true);
        assertEquals(404, accept(first, person).status(), "the older link is dead");
        assertEquals(200, accept(second, person).status());
        assertEquals(List.of("REVIEWER:TOEIC:-"), grantsOf(person));
    }

    @Test
    void theListShowsPendingInvitationsWithoutTokens() {
        UUID s = sysAdmin();
        UUID a = org("CENTER");
        String invitee = email("listed");
        assertEquals(201, post("/api/orgs/" + a + "/invitations", Map.of("email", invitee, "roles", roles(role("LEARNER", null))), s, null).status());
        Res list = get("/api/orgs/" + a + "/invitations", s, null);
        assertEquals(200, list.status(), list.raw());
        assertTrue(list.raw().contains(invitee));
        assertFalse(list.raw().toLowerCase().contains("token"), list.raw());
        // another organisation's admin sees none of it
        UUID b = org("CENTER");
        UUID adminB = orgAdmin(b);
        assertEquals(404, get("/api/orgs/" + a + "/invitations", adminB, b).status());
        Res own = get("/api/orgs/" + b + "/invitations", adminB, b);
        assertEquals(200, own.status());
        assertFalse(own.raw().contains(invitee));
    }

    @Test
    void validatesTheRequest() {
        UUID s = sysAdmin();
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("email", "not-an-email", "roles", roles(role("AUTHOR", "IELTS"))),
                Map.of("email", email("x"), "roles", List.of()),
                Map.of("email", email("x"), "roles", roles(role("AUTHOR", null))),
                Map.of("email", email("x"), "roles", roles(role("AUTHOR", "KLINGON"))),
                Map.of("email", email("x"), "roles", roles(role("ORG_ADMIN", null))),
                Map.of("email", email("x"), "roles", roles(role("GOD", null))))) {
            Res r = post("/api/admin/invitations", bad, s, null);
            assertEquals(400, r.status(), bad + " -> " + r.raw());
            assertTrue(List.of("urn:e9x:problem:validation-failed", "urn:e9x:problem:malformed-request").contains(r.problemType()), r.raw());
        }
        UUID a = org("CENTER");
        Res sysAdminInOrg = post("/api/orgs/" + a + "/invitations", Map.of("email", email("x"), "roles", roles(role("SYS_ADMIN", null))), s, null);
        assertEquals(400, sysAdminInOrg.status(), "SYS_ADMIN belongs to English9x only: " + sysAdminInOrg.raw());
        assertEquals(0, su.queryForObject("select count(*) from invitations where org_id = ?", Integer.class, a));
    }

    @Test
    void anInviterCannotFloodWithMail() {
        UUID s = sysAdmin();
        int limit = 50;
        int created = 0;
        for (int i = 0; i < limit + 3; i++) {
            Res r = inviteStaff(s, email("flood"), "AUTHOR", "IELTS");
            if (r.status() == 201) {
                created++;
            } else {
                assertEquals(429, r.status(), r.raw());
                assertEquals("urn:e9x:problem:too-many-invitations", r.problemType());
            }
        }
        assertEquals(limit, created, "e9x.org.invitation-hourly-limit (default 50) per inviter");
    }

    @Test
    void aSysAdminInvitedFromEnglish9xKeepsNoOrganisationMembership() {
        UUID s = sysAdmin();
        String invitee = email("sysadm");
        Res inv = post("/api/admin/invitations", Map.of("email", invitee, "roles", roles(role("SYS_ADMIN", null))), s, null);
        assertEquals(201, inv.status(), inv.raw());
        UUID person = user(invitee, true);
        assertEquals(200, accept(tokenFrom(TestAuth.SMTP.await(invitee)), person).status());
        assertEquals(List.of("SYS_ADMIN:-:-"), grantsOf(person));
    }
}
