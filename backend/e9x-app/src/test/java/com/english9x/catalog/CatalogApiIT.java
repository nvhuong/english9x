package com.english9x.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.OrgIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** GET /api/catalog/exams: what the SPA offers where an exam is chosen (role pickers, later the CMS). The same for everybody who is signed in. */
class CatalogApiIT extends OrgIT {

    @Test
    void listsTheExamsWithSectionsAndSkills() {
        UUID u = user("any");
        Res r = get("/api/catalog/exams", u, null);
        assertEquals(200, r.status(), r.raw());
        assertEquals(2, r.body().size());
        var ielts = r.body().get(0).path("code").asText().equals("IELTS") ? r.body().get(0) : r.body().get(1);
        assertEquals("IELTS", ielts.path("code").asText());
        assertEquals(4, ielts.path("sections").size());
        assertEquals("AC", ielts.path("variants").get(0).asText());
        assertTrue(ielts.path("scoreSkills").toString().contains("READING_GT"));
        assertFalse(r.raw().contains("answer"), "nothing about answers is in the catalog list");
    }

    @Test
    void sameAnswerInAnyContext() {
        UUID u = user("member");
        UUID org = org("CENTER");
        member(org, u);
        assertEquals(get("/api/catalog/exams", u, null).raw(), get("/api/catalog/exams", u, org).raw());
    }

    @Test
    void requiresASignedInUser() {
        assertEquals(401, call(HttpMethod.GET, "/api/catalog/exams", null, (String) null, java.util.Map.of()).status());
    }
}
