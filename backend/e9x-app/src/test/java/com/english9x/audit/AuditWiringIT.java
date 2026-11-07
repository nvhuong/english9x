package com.english9x.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.OrgIT;
import com.english9x.support.TestAuth;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The org module's actions leave their record (F57): org create, role change, invitation send / revoke / accept. Through the real
 * endpoints and the real database, as the people who do them. A refused attempt leaves nothing; a record that cannot be written
 * stops the action.
 */
class AuditWiringIT extends OrgIT {

    private JsonNode one(String where, Object... args) {
        List<String> rows = su.queryForList("select row_to_json(a)::text from audit_log a where " + where + " order by seq", String.class, args);
        assertEquals(1, rows.size(), "exactly one record for " + where + ": " + rows);
        try {
            return JSON.readTree(rows.get(0));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long count(String where, Object... args) {
        return su.queryForObject("select count(*) from audit_log where " + where, Long.class, args);
    }

    private static List<String> texts(JsonNode array) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void creatingAnOrganisationIsRecorded() {
        UUID sys = sysAdmin();
        Res c = post("/api/admin/organizations", Map.of("name", "Audit Org " + UUID.randomUUID(), "type", "ENTERPRISE"), sys, null);
        assertEquals(201, c.status(), c.raw());
        String id = c.body().path("id").asText();
        JsonNode a = one("action = 'org.create' and target_id = ?", id);
        assertEquals(sys.toString(), a.path("actor_id").asText());
        assertEquals("USER", a.path("actor_kind").asText());
        assertEquals("organization", a.path("target_type").asText());
        assertTrue(a.path("org_id").isNull(), "created from the English9x context");
        assertEquals(c.body().path("name").asText(), a.path("detail").path("name").asText());
        assertEquals("ENTERPRISE", a.path("detail").path("type").asText());

        // refused: no record
        UUID outsider = user("outsider");
        long before = count("action = 'org.create'");
        assertEquals(403, post("/api/admin/organizations", Map.of("name", "Nope", "type", "CENTER"), outsider, null).status());
        assertEquals(400, post("/api/admin/organizations", Map.of("name", "  ", "type", "CENTER"), sys, null).status());
        assertEquals(before, count("action = 'org.create'"));
    }

    @Test
    void aRoleChangeRecordsWhoChangedWhomFromWhatToWhat() {
        UUID a = org("CENTER");
        UUID admin = orgAdmin(a);
        UUID member = user("member");
        grant(member, a, null, "LEARNER");
        Res r = call(HttpMethod.PUT, "/api/orgs/" + a + "/role-grants",
                Map.of("userId", member, "roles", List.of(role("LEARNER", null), role("AUTHOR", "IELTS"))), admin, a);
        assertEquals(200, r.status(), r.raw());
        JsonNode rec = one("action = 'role.change' and target_id = ?", member.toString());
        assertEquals(admin.toString(), rec.path("actor_id").asText());
        assertEquals(a.toString(), rec.path("org_id").asText(), "the context the admin acted in");
        assertEquals("user", rec.path("target_type").asText());
        assertEquals(a.toString(), rec.path("detail").path("orgId").asText());
        assertEquals(List.of("LEARNER"), texts(rec.path("detail").path("before")));
        assertEquals(List.of("AUTHOR/IELTS", "LEARNER"), texts(rec.path("detail").path("after")).stream().sorted().toList());

        // the same roles again changes nothing and records nothing
        long n = count("action = 'role.change' and target_id = ?", member.toString());
        assertEquals(200, call(HttpMethod.PUT, "/api/orgs/" + a + "/role-grants",
                Map.of("userId", member, "roles", List.of(role("LEARNER", null), role("AUTHOR", "IELTS"))), admin, a).status());
        assertEquals(n, count("action = 'role.change' and target_id = ?", member.toString()));

        // a SYS_ADMIN acting for the organisation from the English9x context: no organisation context, the organisation is in the detail
        UUID sys = sysAdmin();
        assertEquals(200, call(HttpMethod.PUT, "/api/orgs/" + a + "/role-grants",
                Map.of("userId", member, "roles", List.of(role("LEARNER", null))), sys, null).status());
        JsonNode bySys = one("action = 'role.change' and target_id = ? and actor_id = ?", member.toString(), sys);
        assertTrue(bySys.path("org_id").isNull());
        assertEquals(a.toString(), bySys.path("detail").path("orgId").asText());
        assertEquals(List.of("AUTHOR/IELTS", "LEARNER"), texts(bySys.path("detail").path("before")).stream().sorted().toList());
        assertEquals(List.of("LEARNER"), texts(bySys.path("detail").path("after")));
    }

    @Test
    void aRefusedRoleChangeLeavesNoRecord() {
        UUID a = org("CENTER");
        UUID admin = orgAdmin(a);
        UUID learner = user("learner");
        grant(learner, a, null, "LEARNER");
        UUID outsider = user("outsider");
        long before = count("action = 'role.change'");
        // last org admin demoted: 409; a learner trying: 403; a non-member target: 404; bad shape: 400
        assertEquals(409, call(HttpMethod.PUT, "/api/orgs/" + a + "/role-grants", Map.of("userId", admin, "roles", List.of(role("LEARNER", null))), admin, a).status());
        assertEquals(403, call(HttpMethod.PUT, "/api/orgs/" + a + "/role-grants", Map.of("userId", learner, "roles", List.of(role("ORG_ADMIN", null))), learner, a).status());
        assertEquals(404, call(HttpMethod.PUT, "/api/orgs/" + a + "/role-grants", Map.of("userId", outsider, "roles", List.of(role("LEARNER", null))), admin, a).status());
        assertEquals(400, call(HttpMethod.PUT, "/api/orgs/" + a + "/role-grants", Map.of("userId", learner, "roles", List.of(role("AUTHOR", null))), admin, a).status());
        assertEquals(before, count("action = 'role.change'"));
    }

    @Test
    void invitationsAreRecordedWithoutTheirTokenAndAcceptingIsRecordedAsTheAcceptor() {
        UUID sys = sysAdmin();
        UUID a = org("CENTER");
        String invitee = email("invitee");
        Res inv = post("/api/orgs/" + a + "/invitations", Map.of("email", invitee.toUpperCase(), "roles", List.of(role("LEARNER", null))), sys, null);
        assertEquals(201, inv.status(), inv.raw());
        String invId = inv.body().path("id").asText();
        JsonNode sent = one("action = 'invitation.send' and target_id = ?", invId);
        assertEquals(sys.toString(), sent.path("actor_id").asText());
        assertEquals("invitation", sent.path("target_type").asText());
        assertEquals(a.toString(), sent.path("detail").path("orgId").asText());
        assertEquals(invitee, sent.path("detail").path("email").asText(), "the address, as the brief lists it, stored the way the invitation stores it");
        assertEquals(List.of("LEARNER"), texts(sent.path("detail").path("roles")));
        assertEquals(0, sent.path("detail").path("superseded").asInt());

        // inviting the same address again retires the older link: the new record says so
        String second = post("/api/orgs/" + a + "/invitations", Map.of("email", invitee, "roles", List.of(role("LEARNER", null))), sys, null).body().path("id").asText();
        assertEquals(1, one("action = 'invitation.send' and target_id = ?", second).path("detail").path("superseded").asInt());

        // the mail carries the token; it is accepted by the invited person, who is the actor
        String first = TestAuth.SMTP.await(invitee).linkParam("token");
        String token = TestAuth.SMTP.await(invitee, 1).linkParam("token");
        UUID person = user(invitee, true);
        Res ok = post("/api/invitations/" + token + "/accept", null, person, null);
        assertEquals(200, ok.status(), ok.raw());
        JsonNode accepted = one("action = 'invitation.accept' and target_id = ?", second);
        assertEquals(person.toString(), accepted.path("actor_id").asText(), "the accepting person, not the inviter");
        assertTrue(accepted.path("org_id").isNull(), "the context the person was in (personal); the organisation joined is in the detail");
        assertEquals(a.toString(), accepted.path("detail").path("orgId").asText());
        assertEquals(List.of("LEARNER"), texts(accepted.path("detail").path("roles")));
        assertFalse(accepted.path("detail").has("email"), "the acceptor's address is not copied into the log");

        // accepting from inside an organisation context records that context as the owner organisation
        UUID b = org("CENTER");
        UUID other = user("other-org-person");
        member(b, other);
        String invitee2 = email("invitee2");
        post("/api/orgs/" + a + "/invitations", Map.of("email", invitee2, "roles", List.of(role("LEARNER", null))), sys, null);
        String token2 = TestAuth.SMTP.await(invitee2).linkParam("token");
        UUID person2 = user(invitee2, true);
        member(b, person2);
        assertEquals(200, post("/api/invitations/" + token2 + "/accept", null, person2, b).status());
        assertEquals(b.toString(), one("action = 'invitation.accept' and actor_id = ?", person2).path("org_id").asText());

        // revoking
        String invitee3 = email("invitee3");
        String id3 = post("/api/orgs/" + a + "/invitations", Map.of("email", invitee3, "roles", List.of(role("LEARNER", null))), sys, null).body().path("id").asText();
        String token3 = TestAuth.SMTP.await(invitee3).linkParam("token");
        assertEquals(204, call(HttpMethod.DELETE, "/api/orgs/" + a + "/invitations/" + id3, null, sys, null).status());
        JsonNode revoked = one("action = 'invitation.revoke' and target_id = ?", id3);
        assertEquals(sys.toString(), revoked.path("actor_id").asText());
        assertEquals(invitee3, revoked.path("detail").path("email").asText());
        assertEquals(a.toString(), revoked.path("detail").path("orgId").asText());
        // revoking again, or something that is not there: refused, nothing recorded
        assertEquals(404, call(HttpMethod.DELETE, "/api/orgs/" + a + "/invitations/" + id3, null, sys, null).status());
        assertEquals(1, count("action = 'invitation.revoke' and target_id = ?", id3));

        // an English9x (internal) invitation: no organisation
        String invitee4 = email("staff");
        String id4 = post("/api/admin/invitations", Map.of("email", invitee4, "roles", List.of(role("AUTHOR", "IELTS"))), sys, null).body().path("id").asText();
        JsonNode staff = one("action = 'invitation.send' and target_id = ?", id4);
        assertTrue(staff.path("detail").path("orgId").isNull());
        assertEquals(List.of("AUTHOR/IELTS"), texts(staff.path("detail").path("roles")));

        // refused attempts leave nothing: a wrong person's token, a forged token, an outsider inviting
        long n = count("action like 'invitation.%'");
        String invitee5 = email("mismatch");
        post("/api/orgs/" + a + "/invitations", Map.of("email", invitee5, "roles", List.of(role("LEARNER", null))), sys, null);
        String token5 = TestAuth.SMTP.await(invitee5).linkParam("token");
        long afterSend = count("action like 'invitation.%'");
        assertEquals(n + 1, afterSend);
        assertEquals(403, post("/api/invitations/" + token5 + "/accept", null, user("not-the-invitee"), null).status());
        assertEquals(404, post("/api/invitations/" + "A".repeat(43) + "/accept", null, person, null).status());
        assertEquals(403, post("/api/orgs/" + a + "/invitations", Map.of("email", email("x"), "roles", List.of(role("LEARNER", null))), user("outsider"), null).status());
        assertEquals(afterSend, count("action like 'invitation.%'"));

        // no token, digest or secret in any record: the whole table, as text
        for (String t : List.of(first, token, token2, token3, token5)) {
            assertEquals(0, count("row_to_json(audit_log)::text like ?", "%" + t + "%"), "the token never reaches the log");
            assertEquals(0, count("row_to_json(audit_log)::text like ?", "%" + java.util.HexFormat.of().formatHex(sha256(t)) + "%"), "nor its digest");
        }
        assertEquals(0, count("actor_id in (?, ?, ?) and lower(row_to_json(audit_log)::text) ~ '(token|password|secret|hash)'", sys, person, person2),
                "no such word, as key or value, in any record of these people");
    }

    private static byte[] sha256(String s) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void whenTheRecordCannotBeWrittenTheActionFails() {
        UUID sys = sysAdmin();
        String name = "Unrecorded " + UUID.randomUUID();
        // take the privilege away for a moment: the log is the one thing that can stop the action
        su.execute("revoke insert on audit_log from e9x_app");
        try {
            Res r = post("/api/admin/organizations", Map.of("name", name, "type", "CENTER"), sys, null);
            assertEquals(500, r.status(), r.raw());
            assertEquals(0, su.queryForObject("select count(*) from organizations where name = ?", Integer.class, name), "the organisation was rolled back with the failed record");
            UUID a = org("CENTER");
            String invitee = email("unrecorded");
            assertEquals(500, post("/api/orgs/" + a + "/invitations", Map.of("email", invitee, "roles", List.of(role("LEARNER", null))), sys, null).status());
            assertEquals(0, su.queryForObject("select count(*) from invitations where email = ?", Integer.class, invitee));
            assertEquals(0, TestAuth.SMTP.countAfterSettling(invitee), "no mail for an invitation that was rolled back");
        } finally {
            su.execute("grant insert (action, target_type, target_id, detail) on audit_log to e9x_app");
        }
        assertEquals(201, post("/api/admin/organizations", Map.of("name", name, "type", "CENTER"), sys, null).status());
    }
}
