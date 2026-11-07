package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** GET /api/me/context: the person's organisations and grants, which the SPA turns into its switcher and role store. */
class MeContextIT extends OrgIT {

    @Test
    void listsOnlyTheCallersOrganisationsAndGrants() {
        UUID a = org("CENTER");
        UUID b = org("ENTERPRISE");
        UUID other = org("CENTER");
        UUID u = user("me");
        grant(u, a, "IELTS", "TEACHER");
        grant(u, b, null, "ORG_ADMIN");
        grant(u, null, "TOEIC", "AUTHOR");
        UUID stranger = user("stranger");
        grant(stranger, other, null, "ORG_ADMIN");

        Res r = get("/api/me/context", u, null);
        assertEquals(200, r.status(), r.raw());
        List<String> orgs = new ArrayList<>();
        r.body().path("organizations").forEach(o -> orgs.add(o.path("id").asText() + ":" + o.path("type").asText()));
        assertEquals(List.of(a + ":CENTER", b + ":ENTERPRISE").stream().sorted().toList(), orgs.stream().sorted().toList());
        assertFalse(r.raw().contains(other.toString()), "another organisation is invisible");
        List<String> grants = new ArrayList<>();
        for (JsonNode g : r.body().path("grants")) {
            grants.add(g.path("role").asText() + ":" + g.path("examCode").asText("-") + ":" + g.path("orgId").asText("-"));
        }
        assertEquals(List.of("AUTHOR:TOEIC:-", "ORG_ADMIN:-:" + b, "TEACHER:IELTS:" + a).stream().sorted().toList(), grants.stream().sorted().toList());
        assertFalse(r.raw().contains("email"), "no personal data of anybody in this answer");
    }

    @Test
    void aSysAdminSeesOnlyTheirOwnMembershipsHere() {
        UUID s = sysAdmin();
        org("CENTER");
        Res r = get("/api/me/context", s, null);
        assertEquals(200, r.status(), r.raw());
        assertEquals(0, r.body().path("organizations").size(), "the full list lives at /api/admin/organizations");
        assertTrue(r.raw().contains("SYS_ADMIN"));
    }

    @Test
    void requiresASignedInUser() {
        assertEquals(401, call(org.springframework.http.HttpMethod.GET, "/api/me/context", null, (String) null, java.util.Map.of()).status());
    }
}
