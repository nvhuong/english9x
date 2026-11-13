package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Personal-data erasure (spec §15, Nghị định 13/2023) through the real stack. The audit log keeps ids only; a name or address is read at view time from
 * users / invitations. So after a SYS_ADMIN erases an invited person: their email string is in NO column of audit_log (it never was), it is gone from users
 * and invitations, and the audit API still answers 200 — now showing a tombstone instead of the address.
 */
class PersonalDataErasureIT extends OrgIT {

    @Test
    void erasingAnInvitedPersonRemovesTheirEmailEverywhereAndTheAuditApiStillAnswers() {
        String email = "gdpr.person@x.test";
        UUID person = user(email, true);
        UUID org = org("CENTER");
        UUID admin = orgAdmin(org);

        assertEquals(201, post("/api/orgs/" + org + "/invitations", Map.of("email", email, "roles", List.of(role("LEARNER", null))), admin, org).status());

        UUID sys = sysAdmin();
        // before: the audit screen shows the invited address (read live from the invitation), but the stored log holds no address
        Res before = get("/api/admin/audit-log?action=invitation.send", sys, null);
        assertEquals(200, before.status(), before.raw());
        assertTrue(before.raw().contains(email), "the audit screen shows the invited address before erasure");
        assertEquals(0, (int) su.queryForObject("select count(*) from audit_log where detail::text ilike ?", Integer.class, "%" + email + "%"),
                "the audit log itself never stored the address");

        // erase the person
        assertEquals(204, call(HttpMethod.DELETE, "/api/admin/users/" + person + "/personal-data", null, sys, null).status());

        // the address is gone from the source tables
        assertEquals(0, (int) su.queryForObject("select count(*) from users where email = ?", Integer.class, email), "gone from users");
        assertEquals(0, (int) su.queryForObject("select count(*) from invitations where email = ?", Integer.class, email), "gone from invitations");
        // and it appears in NO column of audit_log (the whole row cast to text)
        assertEquals(0, (int) su.queryForObject("select count(*) from audit_log where cast(audit_log as text) ilike ?", Integer.class, "%" + email + "%"),
                "the email is in no audit_log column");

        // the audit API still answers 200 and no longer carries the address (it reads the tombstoned invitation now)
        Res after = get("/api/admin/audit-log?action=invitation.send", sys, null);
        assertEquals(200, after.status(), after.raw());
        assertFalse(after.raw().contains(email), "the audit screen no longer shows the erased address");

        // the erasure itself is audited, ids only
        assertEquals(1, (int) su.queryForObject("select count(*) from audit_log where action = 'personal-data.erase' and target_id = ?", Integer.class, person.toString()));
    }
}
