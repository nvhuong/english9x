package com.english9x.course;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Private organisation content (F51): a CENTRE authors content with the ordinary CMS (P2/P3.1) in its own context; its PUBLISHED-but-not-shared course is
 * the centre's own. Row-Level Security (courses_select_own_org vs courses_select_public) keeps it visible to the centre and invisible to every other
 * organisation and to B2C. No new CMS: this proves the existing scoping.
 */
class CenterContentIT extends CourseIT {

    private List<String> catalogCodes(UUID asUser, UUID inOrg) {
        List<String> codes = new ArrayList<>();
        get("/api/catalog/courses", asUser, inOrg).body().forEach(n -> codes.add(n.path("code").asText()));
        return codes;
    }

    @Test
    void invisibleToOtherOrgsAndB2c() {
        Staff a = staff("TOEIC");
        Res course = publishedCourse(a, "FREE");
        UUID courseId = UUID.fromString(course.body().path("courseId").asText());
        String code = su.queryForObject("select code from courses where id = ?", String.class, courseId);
        assertEquals("NONE", su.queryForObject("select shared_status from courses where id = ?", String.class, courseId), "the centre has not shared it");
        assertEquals(a.org(), su.queryForObject("select owner_org_id from courses where id = ?", UUID.class, courseId), "it is the centre's own");

        // the owner's context reaches its own private course by code (RLS courses_select_own_org)
        assertEquals(200, get("/api/catalog/courses/" + code, a.author(), a.org()).status(), "the centre sees its own content");

        // another centre and a B2C learner never do (not a PUBLISHED English9x course, not APPROVED-shared, not their organisation)
        Staff b = staff("TOEIC");
        assertEquals(404, get("/api/catalog/courses/" + code, b.author(), b.org()).status(), "another centre cannot reach it");
        UUID b2c = user("cc-b2c");
        assertEquals(404, get("/api/catalog/courses/" + code, b2c, null).status(), "a B2C learner cannot reach it");

        // the public catalog list carries only English9x and APPROVED-shared courses: a centre's private one is in no list, not even the owner's public one
        assertFalse(catalogCodes(b.author(), b.org()).contains(code), "not in another centre's catalog");
        assertFalse(catalogCodes(b2c, null).contains(code), "not in a B2C learner's catalog");
        assertFalse(catalogCodes(a.author(), a.org()).contains(code), "nor the public catalog of the owner: private content is surfaced in the org tools, not the public pool");

        // the course version and its chapters are likewise invisible to another centre under RLS (independent of the endpoint)
        UUID version = su.queryForObject("select id from course_versions where course_id = ? and status = 'PUBLISHED'", UUID.class, courseId);
        assertTrue(rlsCanSeeVersion(a.author(), a.org(), version), "the owner's staff sees its version");
        assertFalse(rlsCanSeeVersion(b.author(), b.org(), version), "another centre's staff does not");
        assertFalse(rlsCanSeeVersion(b2c, null, version), "a B2C learner does not");
    }

    /** Reads course_versions as e9x_app in the given context (the request role, RLS applied), the way a request would. */
    private boolean rlsCanSeeVersion(UUID asUser, UUID inOrg, UUID version) {
        return get("/api/catalog/courses/" + su.queryForObject("select code from courses c join course_versions v on v.course_id = c.id where v.id = ?",
                String.class, version), asUser, inOrg).status() == 200;
    }
}
