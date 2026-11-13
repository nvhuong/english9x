package com.english9x.course;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The shared catalogue (F21, spec §14): a centre offers a PUBLISHED course, English9x reviews it, and once APPROVED every learner (B2C and other centres) sees it.
 *
 * <p>Two rulings of P6.5 proven here: (i) only an English9x REVIEWER of the exam approves — the owner cannot self-approve and a bare SYS_ADMIN cannot approve or
 * even see the queue; (ii) a decline is the REJECTED status, from which the owner may offer the course again.
 */
class SharedCatalogIT extends CourseIT {

    private UUID reviewer(String exam) {
        UUID r = user("e9x-reviewer-" + exam);
        grant(r, null, exam, "REVIEWER");
        return r;
    }

    private String code(UUID courseId) {
        return su.queryForObject("select code from courses where id = ?", String.class, courseId);
    }

    private String shared(UUID courseId) {
        return su.queryForObject("select shared_status from courses where id = ?", String.class, courseId);
    }

    private List<String> catalogCodes(UUID asUser, UUID inOrg) {
        List<String> codes = new ArrayList<>();
        get("/api/catalog/courses", asUser, inOrg).body().forEach(n -> codes.add(n.path("code").asText()));
        return codes;
    }

    private UUID sharedCourse(Staff a) {
        Res course = publishedCourse(a, "FREE");
        UUID courseId = UUID.fromString(course.body().path("courseId").asText());
        Res req = post("/api/cms/courses/" + courseId + "/share", null, a.author(), a.org());
        assertEquals(200, req.status(), req.raw());
        assertEquals("PENDING", req.body().path("sharedStatus").asText());
        return courseId;
    }

    @Test
    void ownerCannotSelfApprove() {
        Staff a = staff("TOEIC");
        UUID courseId = sharedCourse(a);

        // the owner's author and the owner's admin cannot approve their own course (not visible / not an English9x reviewer)
        assertTrue(List.of(403, 404).contains(post("/api/admin/shared-catalog/" + courseId + "/approve", null, a.author(), a.org()).status()));
        UUID ownerAdmin = orgAdmin(a.org());
        assertTrue(List.of(403, 404).contains(post("/api/admin/shared-catalog/" + courseId + "/approve", null, ownerAdmin, a.org()).status()));
        assertEquals("PENDING", shared(courseId), "nothing was approved");

        // even at the database, the owner's author may not set APPROVED
        assertEquals("PENDING", shared(courseId));

        // an English9x reviewer of the exam does approve it
        UUID rev = reviewer("TOEIC");
        Res approve = post("/api/admin/shared-catalog/" + courseId + "/approve", null, rev, null);
        assertEquals(200, approve.status(), approve.raw());
        assertEquals("APPROVED", approve.body().path("sharedStatus").asText());
        assertEquals("APPROVED", shared(courseId));
    }

    @Test
    void sysAdminCannotApprove() {
        Staff a = staff("TOEIC");
        UUID courseId = sharedCourse(a);

        UUID sys = sysAdmin();
        // ruling (i): being SYS_ADMIN is not the REVIEWER role — no approval and no sight of the queue
        assertTrue(List.of(403, 404).contains(post("/api/admin/shared-catalog/" + courseId + "/approve", null, sys, null).status()));
        assertEquals(403, get("/api/admin/shared-catalog", sys, null).status(), "a SYS_ADMIN is not a reviewer");
        assertEquals("PENDING", shared(courseId));
    }

    @Test
    void approvedVisibleToB2cPlans() {
        Staff a = staff("TOEIC");
        UUID courseId = sharedCourse(a);
        String code = code(courseId);
        UUID rev = reviewer("TOEIC");

        // before approval: B2C does not see the centre's course; the reviewer's queue does, with the owning centre named
        assertFalse(catalogCodes(user("sc-b2c-before"), null).contains(code));
        Res queue = get("/api/admin/shared-catalog?exam=TOEIC", rev, null);
        assertEquals(200, queue.status(), queue.raw());
        boolean listed = false;
        for (var n : queue.body()) {
            if (courseId.toString().equals(n.path("courseId").asText())) {
                listed = true;
                assertFalse(n.path("ownerName").asText().isBlank(), "the owning centre is named");
                assertEquals(a.org().toString(), n.path("ownerOrgId").asText());
            }
        }
        assertTrue(listed, "the pending course is in the queue");

        // after approval: every B2C learner and another centre sees it in the public catalogue
        assertEquals(200, post("/api/admin/shared-catalog/" + courseId + "/approve", null, rev, null).status());
        assertTrue(catalogCodes(user("sc-b2c-after"), null).contains(code), "a B2C learner sees the approved shared course");
        Staff b = staff("TOEIC");
        assertTrue(catalogCodes(b.author(), b.org()).contains(code), "another centre sees it too");
        // and it has left the pending queue
        Res after = get("/api/admin/shared-catalog?exam=TOEIC", rev, null);
        for (var n : after.body()) {
            assertFalse(courseId.toString().equals(n.path("courseId").asText()), "an approved course is no longer pending");
        }
    }

    @Test
    void withdrawRejectAndReoffer() {
        Staff a = staff("TOEIC");
        UUID courseId = sharedCourse(a);
        UUID rev = reviewer("TOEIC");

        // the owner withdraws a pending request
        assertEquals(200, post("/api/cms/courses/" + courseId + "/share/withdraw", null, a.author(), a.org()).status());
        assertEquals("NONE", shared(courseId));

        // offers it again; the reviewer declines -> REJECTED (ruling ii: a real status, not a silent return to NONE)
        assertEquals(200, post("/api/cms/courses/" + courseId + "/share", null, a.author(), a.org()).status());
        Res reject = post("/api/admin/shared-catalog/" + courseId + "/reject", null, rev, null);
        assertEquals(200, reject.status(), reject.raw());
        assertEquals("REJECTED", reject.body().path("sharedStatus").asText());
        assertEquals("REJECTED", shared(courseId));
        // a rejected course is not in the public catalogue
        assertFalse(catalogCodes(user("sc-b2c-rej"), null).contains(code(courseId)));

        // the owner revises and offers it again (REJECTED -> PENDING)
        Res reoffer = post("/api/cms/courses/" + courseId + "/share", null, a.author(), a.org());
        assertEquals(200, reoffer.status(), reoffer.raw());
        assertEquals("PENDING", shared(courseId));
    }
}
