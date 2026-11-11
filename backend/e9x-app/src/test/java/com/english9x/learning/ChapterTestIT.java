package com.english9x.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.domain.learning.LessonStatus;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Chapter tests (F23): the attempts the course allows, the best score kept, and the progress the lesson gate reads. */
class ChapterTestIT extends LearningIT {

    @Test
    void retakeLimitEnforced() {
        Course c = course("FREE", 2);
        UUID learner = learner(c);
        Res first = sit(c, learner, false);
        assertEquals(200, first.status(), first.raw());
        assertEquals(1, first.body().path("attemptNo").asInt());
        assertEquals(1, first.body().path("attemptsLeft").asInt());
        assertEquals(200, sit(c, learner, true).status());
        Res third = sit(c, learner, true);
        assertEquals(409, third.status(), third.raw());
        assertTrue(third.raw().contains("learning-attempts-exhausted"));
        assertEquals(2, su.queryForObject("select count(*) from chapter_test_attempts where user_id = ?", Integer.class, learner));
        assertFalse(get("/api/learning/chapter-tests/" + c.test(), learner, c.staff().org()).body().path("canRetake").asBoolean());
    }

    @Test
    void unlimitedWhenTheCourseSetsNoLimit() {
        Course c = course("FREE", null);
        UUID learner = learner(c);
        for (int i = 0; i < 4; i++) {
            assertEquals(200, sit(c, learner, false).status());
        }
    }

    @Test
    void bestScoreKept() {
        Course c = course("FREE", null);
        UUID learner = learner(c);
        Res good = sit(c, learner, true);
        assertEquals(1.0, good.body().path("score").asDouble());
        assertTrue(good.body().path("passed").asBoolean());
        Res bad = sit(c, learner, false);
        assertEquals(0.0, bad.body().path("score").asDouble());
        assertFalse(bad.body().path("passed").asBoolean());
        assertEquals(1.0, bad.body().path("bestScore").asDouble(), "a worse attempt does not lower the best score");
        assertEquals(1.0, get("/api/learning/chapter-tests/" + c.test(), learner, c.staff().org()).body().path("bestScore").asDouble());
        Map<String, LessonStatus> progress = as(learner, c.staff().org(), () -> Progress.delegate.progress(learner, versionOf(c)));
        assertEquals(new LessonStatus(true, 1.0), progress.get(c.test().toString()));
    }

    @Test
    void realProgressOpensTheNextLessonUnderStrict() {
        Course c = course("STRICT", null);
        UUID learner = learner(c);
        assertEquals(409, get("/api/learning/chapter-tests/" + c.test(), learner, c.staff().org()).status(), "the test waits for the lesson");
        Res done = post("/api/learning/lessons/" + c.lesson() + "/complete", null, learner, c.staff().org());
        assertEquals(200, done.status(), done.raw());
        assertEquals(200, get("/api/learning/chapter-tests/" + c.test(), learner, c.staff().org()).status());
        assertEquals(200, sit(c, learner, false).status());
        assertEquals(409, open(c, learner, c.lesson2()).status(), "under the pass threshold the next chapter stays shut");
        assertEquals(200, sit(c, learner, true).status());
        assertEquals(200, open(c, learner, c.lesson2()).status(), "passed: it opens");
    }

    @Test
    void progressIsPerPerson() {
        Course c = course("FREE", null);
        UUID a = learner(c);
        UUID b = learner(c);
        assertEquals(200, post("/api/learning/lessons/" + c.lesson() + "/complete", null, a, c.staff().org()).status());
        assertFalse(open(c, b, c.lesson()).body().path("completed").asBoolean());
        assertTrue(open(c, a, c.lesson()).body().path("completed").asBoolean());
        assertEquals(0, as(b, c.staff().org(), () -> su.queryForObject("select count(*) from lesson_progress where user_id = ?", Integer.class, b)));
    }

    @Test
    void strangerCannotSit() {
        Course c = course("FREE", null);
        UUID stranger = stranger(c);
        assertEquals(403, get("/api/learning/chapter-tests/" + c.test(), stranger, c.staff().org()).status());
        assertEquals(403, post("/api/learning/chapter-tests/" + c.test() + "/attempts", Map.of("answers", java.util.List.of()), stranger, c.staff().org()).status());
    }

    private UUID versionOf(Course c) {
        return su.queryForObject("select course_version_id from course_lessons where id = ?", UUID.class, c.test());
    }

    @Test
    void itemNotScoredByMachineIsLeftOutNotRefused() {
        Course c = course("FREE", null);
        UUID learner = learner(c);
        UUID photo = UUID.fromString(get("/api/learning/chapter-tests/" + c.test(), learner, c.staff().org()).body().findValues("questions").get(0).get(0).path("versionId").asText());
        // one item of the test becomes a Writing item: a person marks it, the machine cannot
        su.execute((java.sql.Connection con) -> {
            try (java.sql.Statement st = con.createStatement()) {
                st.execute("set session_replication_role = replica");
                st.executeUpdate("update question_versions set type_code = 'IELTS_W_TASK2' where id = '" + photo + "' and type_code = 'TOEIC_P1_PHOTO'");
                st.execute("set session_replication_role = origin");
            }
            return null;
        });
        Res r = sit(c, learner, true);
        assertEquals(200, r.status(), r.raw());
        assertEquals(1.0, r.body().path("rawMax").asDouble(), "only the machine-scored item counts");
        assertEquals(1.0, r.body().path("score").asDouble());
    }
}
