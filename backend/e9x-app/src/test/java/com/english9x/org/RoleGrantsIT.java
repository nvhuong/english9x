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
        put(a, m, List.of(role("ORG_ADMIN", null), role("TEACHER", "IELTS")), admin, a);
        assertEquals(0, su.queryForObject("""
                select count(*) from role_grants g where g.org_id is not null
                   and not exists (select 1 from org_memberships m where m.org_id = g.org_id and m.user_id = g.user_id)""", Integer.class));
    }
}
