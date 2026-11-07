package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** POST/GET /api/admin/organizations: only a real SYS_ADMIN, in the English9x context. */
class OrganizationAdminIT extends OrgIT {

    @Test
    void aSysAdminCreatesACenterAndAnEnterprise() {
        UUID s = sysAdmin();
        Res c = post("/api/admin/organizations", Map.of("name", "Trung tâm Ánh Dương", "type", "CENTER"), s, null);
        assertEquals(201, c.status(), c.raw());
        assertEquals("Trung tâm Ánh Dương", c.body().path("name").asText());
        assertEquals("CENTER", c.body().path("type").asText());
        Res e = post("/api/admin/organizations", Map.of("name", "Công ty Bình Minh", "type", "ENTERPRISE"), s, null);
        assertEquals(201, e.status(), e.raw());
        assertEquals("ENTERPRISE", su.queryForObject("select type from organizations where id = ?::uuid", String.class, e.body().path("id").asText()));

        Res list = get("/api/admin/organizations", s, null);
        assertEquals(200, list.status(), list.raw());
        assertTrue(list.raw().contains(c.body().path("id").asText()) && list.raw().contains(e.body().path("id").asText()),
                "the SYS_ADMIN sees organisations they do not belong to");
    }

    @Test
    void nobodyElseMay() {
        UUID a = org("CENTER");
        UUID admin = orgAdmin(a);
        UUID author = user("author");
        grant(author, null, "IELTS", "AUTHOR");
        UUID learner = user("learner");
        Map<String, String> body = Map.of("name", "Mine", "type", "CENTER");
        for (Res r : new Res[] {
            post("/api/admin/organizations", body, admin, a),
            post("/api/admin/organizations", body, admin, null),
            post("/api/admin/organizations", body, author, null),
            post("/api/admin/organizations", body, learner, null)}) {
            assertEquals(403, r.status(), r.raw());
            assertEquals("urn:e9x:problem:forbidden", r.problemType());
        }
        assertEquals(401, post("/api/admin/organizations", body, null, null).status());
        assertEquals(403, get("/api/admin/organizations", admin, a).status());
        assertEquals(403, get("/api/admin/organizations", author, null).status());
        assertEquals(0, su.queryForObject("select count(*) from organizations where name = 'Mine'", Integer.class));
    }

    @Test
    void aSysAdminSittingInAnOrganisationContextIsNotInTheEnglish9xContext() {
        // a SYS_ADMIN who is also a plain member of an organisation: inside that organisation they hold no English9x power
        UUID a = org("CENTER");
        UUID s = sysAdmin();
        member(a, s);
        Res r = post("/api/admin/organizations", Map.of("name", "Nope", "type", "CENTER"), s, a);
        assertEquals(403, r.status(), r.raw());
    }

    @Test
    void validatesTheBody() {
        UUID s = sysAdmin();
        for (Map<String, Object> bad : java.util.List.<Map<String, Object>>of(
                Map.of("name", "", "type", "CENTER"),
                Map.of("name", "   ", "type", "CENTER"),
                Map.of("name", "x".repeat(121), "type", "CENTER"),
                Map.of("name", "ok", "type", "SCHOOL"),
                Map.of("type", "CENTER"),
                Map.of("name", "ok"))) {
            Res r = post("/api/admin/organizations", bad, s, null);
            assertEquals(400, r.status(), bad + " -> " + r.raw());
            // an unknown enum value never reaches validation: the body cannot be read
            assertTrue(java.util.List.of("urn:e9x:problem:validation-failed", "urn:e9x:problem:malformed-request").contains(r.problemType()), r.raw());
            if (r.problemType().endsWith("validation-failed")) {
                assertFalse(r.body().path("errors").isEmpty(), "errors[] names the field");
            }
        }
    }

    @Test
    void createdOrganisationHasNoMembersYet() {
        UUID s = sysAdmin();
        Res c = post("/api/admin/organizations", Map.of("name", "Empty", "type", "CENTER"), s, null);
        UUID id = UUID.fromString(c.body().path("id").asText());
        assertEquals(0, su.queryForObject("select count(*) from org_memberships where org_id = ?", Integer.class, id));
        assertEquals(0, su.queryForObject("select count(*) from role_grants where org_id = ?", Integer.class, id),
                "the SYS_ADMIN does not become a member or an admin of what they create");
    }
}
