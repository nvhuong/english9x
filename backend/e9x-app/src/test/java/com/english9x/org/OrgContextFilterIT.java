package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestAuth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * X-Org-Context: absent = "Cá nhân"; a member's organisation is honoured; anything else is a 403 problem+json rendered
 * before MVC, with nothing in it that tells "no such organisation" from "not a member".
 */
class OrgContextFilterIT extends OrgIT {

    @Test
    void absentHeaderMeansThePersonalContext() {
        UUID u = user("p");
        Res r = get("/api/me/context", u, null);
        assertEquals(200, r.status(), r.raw());
        assertTrue(r.body().path("contextOrgId").isNull(), r.raw());
    }

    @Test
    void aMembersOrganisationIsHonoured() {
        UUID a = org("CENTER");
        UUID u = user("m");
        member(a, u);
        Res r = get("/api/me/context", u, a);
        assertEquals(200, r.status(), r.raw());
        assertEquals(a.toString(), r.body().path("contextOrgId").asText());
    }

    @Test
    void aNonMemberAndANonexistentOrganisationGetTheSameBodyApartFromNothing() {
        UUID a = org("CENTER");
        UUID u = user("outsider");
        Res foreign = get("/api/me/context", u, a);
        UUID missing = UUID.randomUUID();
        Res nowhere = get("/api/me/context", u, missing);
        assertEquals(403, foreign.status(), foreign.raw());
        assertEquals(403, nowhere.status(), nowhere.raw());
        assertEquals("urn:e9x:problem:forbidden", foreign.problemType());
        assertEquals(foreign.body(), nowhere.body(), "nothing in the answer tells the two apart");
        assertTrue(foreign.headers().getContentType().toString().startsWith("application/problem+json"));
        assertFalse(foreign.raw().contains(a.toString()), "the id is not echoed: " + foreign.raw());
        assertFalse(nowhere.raw().contains(missing.toString()), "the id is not echoed: " + nowhere.raw());
    }

    @Test
    void malformedValuesAreRefusedTheSameWay() {
        UUID u = user("mal");
        String token = TestAuth.accessToken(u);
        List<String> bad = List.of("not-a-uuid", "", " ", "1-1-1-1-1", UUID.randomUUID() + " ", "0".repeat(32), "{" + UUID.randomUUID() + "}",
                UUID.randomUUID() + "," + UUID.randomUUID(), "null", "personal");
        for (String value : bad) {
            Res r = call(HttpMethod.GET, "/api/me/context", null, token, Map.of("X-Org-Context", value));
            assertEquals(403, r.status(), "value '" + value + "': " + r.raw());
            assertEquals("urn:e9x:problem:forbidden", r.problemType(), value);
        }
    }

    @Test
    void twoHeadersAreRefused() {
        UUID a = org("CENTER");
        UUID u = user("two");
        member(a, u);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(TestAuth.accessToken(u));
        h.add("X-Org-Context", a.toString());
        h.add("X-Org-Context", a.toString());
        ResponseEntity<String> r = http.exchange("/api/me/context", HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertEquals(403, r.getStatusCode().value());
    }

    @Test
    void anUppercaseMemberIdIsTheSameOrganisation() {
        UUID a = org("CENTER");
        UUID u = user("up");
        member(a, u);
        Res r = call(HttpMethod.GET, "/api/me/context", null, TestAuth.accessToken(u), Map.of("X-Org-Context", a.toString().toUpperCase()));
        assertEquals(200, r.status(), r.raw());
        assertEquals(a.toString(), r.body().path("contextOrgId").asText());
    }

    @Test
    void anonymousStaysUnauthorisedWhateverTheHeader() {
        Res r = call(HttpMethod.GET, "/api/me/context", null, (String) null, Map.of("X-Org-Context", UUID.randomUUID().toString()));
        assertEquals(401, r.status(), r.raw());
    }

    @Test
    void aMemberRemovedAfterTheirTokenWasIssuedIsRefusedAtOnce() {
        UUID a = org("CENTER");
        UUID u = user("gone");
        member(a, u);
        assertEquals(200, get("/api/me/context", u, a).status());
        su.update("delete from org_memberships where org_id = ? and user_id = ?", a, u);
        assertEquals(403, get("/api/me/context", u, a).status(), "membership is checked on every request, not read from the token");
    }

    @Test
    void theOrganisationOfOneRequestNeverLeaksIntoTheNext() {
        UUID a = org("CENTER");
        UUID b = org("CENTER");
        UUID u = user("seq");
        member(a, u);
        member(b, u);
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID ctx = i % 3 == 0 ? null : (i % 3 == 1 ? a : b);
            Res r = get("/api/me/context", u, ctx);
            seen.add(r.body().path("contextOrgId").asText("null"));
            assertEquals(ctx == null ? "null" : ctx.toString(), seen.get(i), "request " + i);
        }
    }
}
