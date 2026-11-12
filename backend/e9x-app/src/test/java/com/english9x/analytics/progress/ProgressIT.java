package com.english9x.analytics.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** F24: progress grouped by the plan or seat that grants each course; an ended plan is read only and a course bought again continues. */
class ProgressIT extends ProgressHarness {

    @Test
    void groupsCoursesByPlanSource() {
        Room room = room();
        UUID org = room.staff().org();
        UUID admin = orgAdmin(org);
        UUID learner = user("grouped");
        member(org, learner);
        Lessons a = course("Order course", 2);
        Lessons b = course("Seat course", 1);
        orderEntitlement(learner, a.course(), order(learner, plan("Gói 3 tháng", 3)), Duration.ofDays(2), Duration.ofDays(30));
        seatEntitlement(org, admin, learner, b.course(), Duration.ofDays(1), Duration.ofDays(90));
        complete(learner, a.course(), a.refIds().get(0));
        assign(org, admin, learner, a.lessonIds().get(1), Duration.ofDays(3));

        Res r = get("/api/me/progress", learner, null);
        assertEquals(200, r.status(), r.raw());
        JsonNode plans = r.body().path("plans");
        assertEquals(2, plans.size(), r.raw());
        assertEquals(0, r.body().path("expired").size());
        JsonNode order = plans.get(0).path("source").asText().equals("ORDER") ? plans.get(0) : plans.get(1);
        JsonNode seat = plans.get(0).path("source").asText().equals("SEAT") ? plans.get(0) : plans.get(1);
        assertEquals("Gói 3 tháng", order.path("name").asText());
        assertEquals(3, order.path("coursesMax").asInt());
        assertEquals(1, order.path("coursesUsed").asInt());
        assertTrue(order.path("active").asBoolean());
        assertTrue(order.path("daysLeft").asLong() >= 28 && order.path("daysLeft").asLong() <= 30, order.toString());
        assertEquals(1, order.path("courses").size(), "the seat course is not in the order plan");
        JsonNode ca = order.path("courses").get(0);
        assertEquals(a.course().toString(), ca.path("courseId").asText());
        assertEquals(50, ca.path("percent").asInt());
        assertEquals(1, ca.path("lessonsDone").asInt());
        assertEquals(2, ca.path("lessonsTotal").asInt());
        assertEquals(a.lessonIds().get(1).toString(), ca.path("resumeLessonId").asText());
        assertEquals(1, ca.path("pendingAssignments").asInt());
        assertTrue(seat.path("organisation").asText().startsWith("Org "));
        assertEquals(1, seat.path("courses").size());
        JsonNode cb = seat.path("courses").get(0);
        assertEquals(b.course().toString(), cb.path("courseId").asText());
        assertEquals(0, cb.path("percent").asInt());
        assertEquals(b.lessonIds().get(0).toString(), cb.path("resumeLessonId").asText());
        assertEquals(0, cb.path("pendingAssignments").asInt());
        assertEquals(1, r.body().path("pendingAssignments").size());
        assertEquals(8, r.body().path("weeklyMinutes").size());

        // the organisation's staff see the seat the organisation issued, and nothing of the paid order
        Res staff = get("/api/orgs/" + org + "/learners/" + learner + "/progress", admin, org);
        assertEquals(200, staff.status(), staff.raw());
        assertEquals(1, staff.body().path("plans").size());
        assertEquals("SEAT", staff.body().path("plans").get(0).path("source").asText());
        assertFalse(staff.raw().contains("Order course"), staff.raw());
    }

    @Test
    void expiredPlanReadOnlyAndResumable() {
        Room room = room();
        UUID learner = user("resume");
        member(room.staff().org(), learner);
        Lessons a = course("Resumable", 2);
        orderEntitlement(learner, a.course(), order(learner, plan("Old plan", 2)), Duration.ofDays(60), Duration.ofDays(50)); // ended 10 days ago
        complete(learner, a.course(), a.refIds().get(0));

        JsonNode body = get("/api/me/progress", learner, null).body();
        assertEquals(0, body.path("plans").size());
        assertEquals(1, body.path("expired").size());
        JsonNode old = body.path("expired").get(0);
        assertTrue(old.path("readOnly").asBoolean());
        assertFalse(old.path("active").asBoolean());
        assertEquals(0, old.path("daysLeft").asLong());
        assertEquals(50, old.path("courses").get(0).path("percent").asInt(), "what was learnt is still shown");
        assertNull(old.path("courses").get(0).path("resumeLessonId").textValue(), "an ended plan opens nothing new");

        // bought again: the same lessons, the progress goes on
        orderEntitlement(learner, a.course(), order(learner, plan("New plan", 2)), Duration.ofDays(1), Duration.ofDays(30));
        JsonNode again = get("/api/me/progress", learner, null).body();
        assertEquals(1, again.path("plans").size());
        assertEquals(1, again.path("expired").size());
        JsonNode c = again.path("plans").get(0).path("courses").get(0);
        assertEquals(50, c.path("percent").asInt());
        assertEquals(a.lessonIds().get(1).toString(), c.path("resumeLessonId").asText());
        complete(learner, a.course(), a.refIds().get(1));
        JsonNode done = get("/api/me/progress", learner, null).body().path("plans").get(0).path("courses").get(0);
        assertEquals(100, done.path("percent").asInt());
        assertNull(done.path("resumeLessonId").textValue());
    }

    @Test
    void examsAndScoresOfTheCourse() {
        Room room = room();
        UUID learner = learner(room);
        sat(room, learner, "A", "D");
        JsonNode body = get("/api/me/progress", learner, null).body();
        JsonNode course = body.path("plans").get(0).path("courses").get(0);
        assertEquals(1, course.path("examsTaken").asInt(), body.toString());
        if (!course.path("latestScore").isNull()) {
            assertEquals("OFFICIAL", course.path("latestScore").path("kind").asText());
            assertEquals(1, body.path("scoreSeries").size());
        }
    }

    @Test
    void organisationProgressIsForItsStaffAndItsMembersOnly() {
        Room room = room();
        UUID org = room.staff().org();
        UUID admin = orgAdmin(org);
        UUID teacher = user("teach");
        grant(teacher, org, "TOEIC", "TEACHER");
        UUID learner = user("member");
        member(org, learner);
        UUID plain = user("plain");
        member(org, plain);
        UUID otherOrg = org("CENTER");
        UUID otherAdmin = orgAdmin(otherOrg);
        UUID outsider = user("outsider");
        String path = "/api/orgs/" + org + "/learners/" + learner + "/progress";

        assertEquals(200, get(path, admin, org).status());
        assertEquals(200, get(path, teacher, org).status());
        assertEquals(403, get(path, plain, org).status(), "a learner reads no other learner");
        assertEquals(403, get(path, admin, null).status(), "no organisation context");
        assertEquals(404, get(path, otherAdmin, otherOrg).status(), "another organisation's resource");
        assertEquals(404, get("/api/orgs/" + org + "/learners/" + outsider + "/progress", admin, org).status(), "not a member of the organisation");
        assertEquals(404, get("/api/orgs/" + otherOrg + "/learners/" + learner + "/progress", admin, org).status(), "the path's organisation is not the context's");
        assertEquals(404, get("/api/orgs/" + otherOrg + "/learners/" + learner + "/progress", otherAdmin, otherOrg).status(), "a member of another organisation");
    }
}
