package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** Spec §4: an ENTERPRISE organisation has no AUTHOR, REVIEWER or TEACHER. Enforced by the service and by the database. */
class EnterpriseOrgIT extends OrgIT {

    @Test
    void cannotGrantAuthorReviewerTeacher() {
        UUID e = org("ENTERPRISE");
        UUID admin = orgAdmin(e);
        UUID m = user("emp");
        member(e, m);
        for (String role : List.of("AUTHOR", "REVIEWER", "TEACHER")) {
            Res put = call(HttpMethod.PUT, "/api/orgs/" + e + "/role-grants",
                    Map.of("userId", m.toString(), "roles", List.of(role(role, "IELTS"))), admin, e);
            assertEquals(409, put.status(), role + ": " + put.raw());
            assertEquals("urn:e9x:problem:role-not-allowed", put.problemType());
            Res inv = post("/api/orgs/" + e + "/invitations", Map.of("email", email("x"), "roles", List.of(role(role, "IELTS"))), admin, e);
            assertEquals(409, inv.status(), role + ": " + inv.raw());
            assertEquals("urn:e9x:problem:role-not-allowed", inv.problemType());
        }
        assertEquals(List.of(), grantsOf(m));
        assertEquals(0, su.queryForObject("select count(*) from invitations where org_id = ?", Integer.class, e));
        // what an enterprise does have
        assertEquals(200, call(HttpMethod.PUT, "/api/orgs/" + e + "/role-grants",
                Map.of("userId", m.toString(), "roles", List.of(role("LEARNER", null), role("ORG_ADMIN", null))), admin, e).status());
    }

    @Test
    void aCenterMayHaveThem() {
        UUID c = org("CENTER");
        UUID admin = orgAdmin(c);
        UUID m = user("t");
        member(c, m);
        assertEquals(200, call(HttpMethod.PUT, "/api/orgs/" + c + "/role-grants",
                Map.of("userId", m.toString(), "roles", List.of(role("TEACHER", "IELTS"), role("AUTHOR", "TOEIC"), role("REVIEWER", "IELTS"))), admin, c).status());
        assertEquals(3, grantsOf(m).size());
    }

    @Test
    void theDatabaseRefusesEvenWhenTheServiceIsBypassed() {
        UUID e = org("ENTERPRISE");
        UUID m = user("emp");
        member(e, m);
        Throwable t = assertThrows(org.springframework.dao.DataAccessException.class,
                () -> su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,?,?)", m, e, "IELTS", "TEACHER"));
        assertTrue(t.getMessage().contains("ENTERPRISE"), t.getMessage());
    }

    @Test
    void anOrganisationCannotBecomeAnEnterpriseWhileItHasIncompatibleGrants() {
        UUID c = org("CENTER");
        UUID t = user("teacher");
        grant(t, c, "IELTS", "TEACHER");
        Throwable x = assertThrows(org.springframework.dao.DataAccessException.class,
                () -> su.update("update organizations set type = 'ENTERPRISE' where id = ?", c));
        assertTrue(x.getMessage().contains("ENTERPRISE"), x.getMessage());
        su.update("delete from role_grants where user_id = ?", t);
        su.update("update organizations set type = 'ENTERPRISE' where id = ?", c);
        assertEquals("ENTERPRISE", su.queryForObject("select type from organizations where id = ?", String.class, c));
    }
}
