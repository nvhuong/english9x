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

    // ---- fix round 1 ---------------------------------------------------------------------------------------------------

    @Test
    void revokingThroughAnOwnOrganisationWithAForeignChildIs404AndLeavesTheRowAlone() {
        UUID a = org("CENTER");
        UUID b = org("CENTER");
        UUID adminA = orgAdmin(a);
        UUID adminB = orgAdmin(b);
        String foreignAddress = email("foreign-child");
        assertEquals(201, post("/api/orgs/" + b + "/invitations", Map.of("email", foreignAddress, "roles", roles(role("LEARNER", null))), adminB, b).status());
        UUID foreign = su.queryForObject("select id from invitations where email = ?", UUID.class, foreignAddress);

        Res r = call(org.springframework.http.HttpMethod.DELETE, "/api/orgs/" + a + "/invitations/" + foreign, null, adminA, a);
        assertEquals(404, r.status(), r.raw());
        assertEquals(0, su.queryForObject("select count(*) from invitations where id = ? and revoked_at is not null", Integer.class, foreign), "the foreign row is untouched");
        // the same id through its own organisation does work: the 404 above came from the child check, not from a dead route
        assertEquals(204, call(org.springframework.http.HttpMethod.DELETE, "/api/orgs/" + b + "/invitations/" + foreign, null, adminB, b).status());
        assertEquals(1, su.queryForObject("select count(*) from invitations where id = ? and revoked_at is not null", Integer.class, foreign));
    }

    @Test
    void theHourlyLimitCountsOnePersonAcrossEveryOrganisation() {
        UUID a = org("CENTER");
        UUID b = org("CENTER");
        UUID admin = orgAdmin(a);
        grant(admin, b, null, "ORG_ADMIN");
        for (int i = 0; i < 49; i++) { // sent from organisation B, invisible to RLS while the same person works in A
            su.update("insert into invitations(org_id,email,grants,token_hash,invited_by,expires_at) values (?,?,'[{\"role\":\"LEARNER\",\"exam\":null}]'::jsonb,sha256(?::bytea),?,now()+interval '1 day')",
                    b, "count" + i + "-" + UUID.randomUUID() + "@example.test", UUID.randomUUID().toString(), admin);
        }
        Res fiftieth = post("/api/orgs/" + a + "/invitations", Map.of("email", email("fiftieth"), "roles", roles(role("LEARNER", null))), admin, a);
        assertEquals(201, fiftieth.status(), fiftieth.raw());
        Res over = post("/api/orgs/" + a + "/invitations", Map.of("email", email("over"), "roles", roles(role("LEARNER", null))), admin, a);
        assertEquals(429, over.status(), "50 per person whatever the organisation: " + over.raw());
        assertEquals("urn:e9x:problem:too-many-invitations", over.problemType());
        // somebody else in the same organisation has their own allowance
        UUID other = orgAdmin(a);
        assertEquals(201, post("/api/orgs/" + a + "/invitations", Map.of("email", email("other"), "roles", roles(role("LEARNER", null))), other, a).status());
    }

    @Test
    void theTokenAppearsInNoAcceptResponse() {
        UUID s = sysAdmin();
        String invitee = email("secret");
        assertEquals(201, inviteStaff(s, invitee, "AUTHOR", "IELTS").status());
        String token = tokenFrom(TestAuth.SMTP.await(invitee));
        String path = "/api/invitations/" + token + "/accept";
        UUID right = user(invitee, true);
        UUID wrong = user("wrong-address");
        String unverifiedAddress = email("unverified");
        assertEquals(201, inviteStaff(s, unverifiedAddress, "AUTHOR", "IELTS").status());
        String unverifiedToken = tokenFrom(TestAuth.SMTP.await(unverifiedAddress));
        UUID unverified = user(unverifiedAddress, false);

        java.util.List<Res> responses = new java.util.ArrayList<>();
        Res anonymous = post(path, null, null, null);
        assertEquals(401, anonymous.status(), "the security entry point: " + anonymous.raw());
        responses.add(anonymous);
        Res mismatch = post(path, null, wrong, null);
        assertEquals(403, mismatch.status(), "MVC problem: " + mismatch.raw());
        responses.add(mismatch);
        Res badContext = call(org.springframework.http.HttpMethod.POST, path, null, TestAuth.accessToken(right), Map.of("X-Org-Context", "not-a-uuid"));
        assertEquals(403, badContext.status(), "the context filter: " + badContext.raw());
        responses.add(badContext);
        Res wrongMethod = call(org.springframework.http.HttpMethod.PUT, path, null, right, null);
        assertEquals(405, wrongMethod.status(), "the framework's own problem: " + wrongMethod.raw());
        responses.add(wrongMethod);
        Res ok = post(path, null, right, null);
        assertEquals(200, ok.status(), ok.raw());
        responses.add(ok);
        Res used = post(path, null, right, null);
        assertEquals(404, used.status(), used.raw());
        responses.add(used);
        for (Res r : responses) {
            assertFalse(r.raw().contains(token), "the token is echoed: " + r.raw());
            assertFalse(r.body().has("instance"), "instance names the path on this route: " + r.raw());
        }
        Res notVerified = post("/api/invitations/" + unverifiedToken + "/accept", null, unverified, null);
        assertEquals(403, notVerified.status(), notVerified.raw());
        assertFalse(notVerified.raw().contains(unverifiedToken), notVerified.raw());
        String forged = "A".repeat(43);
        Res forgedAnswer = post("/api/invitations/" + forged + "/accept", null, right, null);
        assertEquals(404, forgedAnswer.status(), forgedAnswer.raw());
        assertFalse(forgedAnswer.raw().contains(forged), forgedAnswer.raw());
        // the rule is this route's, not every problem's
        assertEquals("/api/me/context", get("/api/me/context", null, null).body().path("instance").asText());
    }

    private String statusOf(Res list, String address) {
        for (JsonNode n : list.body()) {
            if (address.equals(n.path("email").asText())) {
                return n.path("status").asText();
            }
        }
        throw new AssertionError(address + " not listed: " + list.raw());
    }

    @Test
    void anInvitationVoidedByTheInvitersLostAuthorityIsNotListedAsPending() {
        UUID a = org("CENTER");
        UUID leaving = orgAdmin(a);
        UUID staying = orgAdmin(a);
        String address = email("voided");
        assertEquals(201, post("/api/orgs/" + a + "/invitations", Map.of("email", address, "roles", roles(role("LEARNER", null))), leaving, a).status());
        String token = tokenFrom(TestAuth.SMTP.await(address));
        assertEquals("PENDING", statusOf(get("/api/orgs/" + a + "/invitations", staying, a), address));

        su.update("delete from role_grants where user_id = ? and role = 'ORG_ADMIN'", leaving);
        assertEquals("VOIDED", statusOf(get("/api/orgs/" + a + "/invitations", staying, a), address), "the list says what accepting would do");
        UUID person = user(address, true);
        assertEquals(404, post("/api/invitations/" + token + "/accept", null, person, null).status());

        // invited by a SYS_ADMIN, whose grant an ORG_ADMIN cannot read: still shown correctly on the organisation's list
        UUID sys = sysAdmin();
        String bySys = email("by-sys");
        assertEquals(201, post("/api/orgs/" + a + "/invitations", Map.of("email", bySys, "roles", roles(role("LEARNER", null))), sys, null).status());
        assertEquals("PENDING", statusOf(get("/api/orgs/" + a + "/invitations", staying, a), bySys));
        su.update("delete from role_grants where user_id = ? and role = 'SYS_ADMIN'", sys);
        assertEquals("VOIDED", statusOf(get("/api/orgs/" + a + "/invitations", staying, a), bySys));
    }

    @Test
    void aNewInvitationLeavesAnExpiredOneExpired() {
        UUID a = org("CENTER");
        UUID admin = orgAdmin(a);
        String address = email("expired-stays");
        assertEquals(201, post("/api/orgs/" + a + "/invitations", Map.of("email", address, "roles", roles(role("LEARNER", null))), admin, a).status());
        su.update("update invitations set expires_at = now() - interval '1 minute' where email = ?", address);
        assertEquals(201, post("/api/orgs/" + a + "/invitations", Map.of("email", address, "roles", roles(role("TEACHER", "IELTS"))), admin, a).status());
        assertEquals(1, su.queryForObject("select count(*) from invitations where email = ? and revoked_at is null and expires_at <= now()", Integer.class, address),
                "the expired row stays EXPIRED, not REVOKED");
        assertEquals(1, su.queryForObject("select count(*) from invitations where email = ? and revoked_at is null and expires_at > now()", Integer.class, address));
        // and revoking an expired one is refused too: only a pending invitation can be revoked
        UUID expired = su.queryForObject("select id from invitations where email = ? and expires_at <= now()", UUID.class, address);
        assertEquals(404, call(org.springframework.http.HttpMethod.DELETE, "/api/orgs/" + a + "/invitations/" + expired, null, admin, a).status());
        assertEquals(0, su.queryForObject("select count(*) from invitations where id = ? and revoked_at is not null", Integer.class, expired));
    }

    @Test
    void twoAdminsInvitingTheSameAddressLeaveOneLiveLink() throws Exception {
        UUID a = org("CENTER");
        UUID first = orgAdmin(a);
        UUID second = orgAdmin(a);
        String address = email("raced");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                java.util.concurrent.CyclicBarrier go = new java.util.concurrent.CyclicBarrier(2);
                java.util.List<java.util.concurrent.Future<Res>> done = new java.util.ArrayList<>();
                for (UUID who : List.of(first, second)) {
                    done.add(pool.submit(() -> {
                        go.await();
                        return post("/api/orgs/" + a + "/invitations", Map.of("email", address, "roles", roles(role("LEARNER", null))), who, a);
                    }));
                }
                for (var f : done) {
                    Res r = f.get();
                    assertEquals(201, r.status(), r.raw());
                }
                assertEquals(1, su.queryForObject("select count(*) from invitations where email = ? and accepted_at is null and revoked_at is null and expires_at > now()", Integer.class, address),
                        "round " + round + ": one live link");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theMailReadsProperlyWithoutAnInviterName() {
        UUID en = sysAdmin();
        su.update("update users set display_name = null, locale = 'en' where id = ?", en);
        String toEn = email("mail-en");
        assertEquals(201, inviteStaff(en, toEn, "AUTHOR", "IELTS").status());
        String bodyEn = TestAuth.SMTP.await(toEn).body();
        assertTrue(bodyEn.contains("You have been invited to join English9x as: Author IELTS."), bodyEn);
        assertFalse(bodyEn.contains("You to join"), bodyEn);

        UUID vi = sysAdmin();
        su.update("update users set display_name = null, locale = 'vi' where id = ?", vi);
        String toVi = email("mail-vi");
        assertEquals(201, inviteStaff(vi, toVi, "AUTHOR", "IELTS").status());
        String bodyVi = TestAuth.SMTP.await(toVi).body();
        assertTrue(bodyVi.contains("Bạn được mời tham gia English9x với vai trò: Biên soạn IELTS."), bodyVi);

        // with a name, both languages name the person
        UUID named = sysAdmin();
        su.update("update users set display_name = 'Lan Anh', locale = 'en' where id = ?", named);
        String toNamed = email("mail-named");
        assertEquals(201, inviteStaff(named, toNamed, "AUTHOR", "IELTS").status());
        assertTrue(TestAuth.SMTP.await(toNamed).body().contains("Lan Anh invited you to join English9x"));
    }
}
