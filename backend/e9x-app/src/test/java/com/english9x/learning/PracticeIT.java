package com.english9x.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Practice is the one place a learner is shown an answer: the question they just answered, in a lesson they may open. */
class PracticeIT extends LearningIT {

    private Res practice(Course c, UUID who, UUID lesson, UUID question, String answer) {
        return post("/api/learning/lessons/" + lesson + "/practice", Map.of("questionVersionId", question.toString(), "answer", answer), who, c.staff().org());
    }

    @Test
    void returnsExplanationImmediately() {
        Course c = course("FREE", null);
        UUID learner = learner(c);
        Res right = practice(c, learner, c.lesson(), c.question(), "D");
        assertEquals(200, right.status(), right.raw());
        assertTrue(right.body().path("correct").asBoolean());
        assertEquals(1.0, right.body().path("earned").asDouble());
        assertEquals(EXPLANATION_SECRET, right.body().path("explanation").asText());
        assertEquals("D", right.body().path("correctAnswer").asText());
        Res wrong = practice(c, learner, c.lesson(), c.question(), "A");
        assertFalse(wrong.body().path("correct").asBoolean());
        assertEquals(0.0, wrong.body().path("earned").asDouble());
        assertEquals(EXPLANATION_SECRET, wrong.body().path("explanation").asText(), "the explanation is shown for a wrong answer too");
        assertEquals(2, su.queryForObject("select count(*) from practice_attempts where user_id = ?", Integer.class, learner));
    }

    @Test
    void learnerWithoutPlanCannotPracticeNorSeeAnswers() {
        Course c = course("FREE", null);
        UUID stranger = stranger(c);
        Res r = practice(c, stranger, c.lesson(), c.question(), "D");
        assertEquals(403, r.status(), r.raw());
        assertFalse(r.raw().contains(EXPLANATION_SECRET));
        assertEquals(403, open(c, stranger, c.lesson()).status());
        assertEquals(0, su.queryForObject("select count(*) from practice_attempts where user_id = ?", Integer.class, stranger));
    }

    @Test
    void onlyAQuestionOfThisLessonAnswers() {
        Course c = course("FREE", null);
        UUID learner = learner(c);
        UUID other = publishedQuestion(P5, c.staff()); // published, but in no lesson of the course
        Res r = practice(c, learner, c.lesson(), other, "D");
        assertEquals(404, r.status(), r.raw());
        assertFalse(r.raw().contains(EXPLANATION_SECRET));
        // the same question named through another lesson of the course (which does not embed it) is refused too
        assertEquals(404, practice(c, learner, c.lesson2(), c.question(), "D").status());
        assertEquals(404, practice(c, learner, c.test(), c.question(), "D").status());
    }

    @Test
    void lessonViewCarriesNoAnswerSide() {
        Course c = course("FREE", null);
        Res r = open(c, learner(c), c.lesson());
        assertEquals(200, r.status(), r.raw());
        assertEquals(c.question().toString(), r.body().path("questions").path(0).path("versionId").asText());
        assertFalse(r.raw().contains(EXPLANATION_SECRET));
        assertFalse(r.raw().contains("answerKey"));
    }

    @Test
    void lockedLessonDoesNotPractice() {
        Course c = course("STRICT", null);
        UUID learner = learner(c);
        assertEquals(409, open(c, learner, c.lesson2()).status());
        assertEquals(409, practice(c, learner, c.lesson2(), c.question(), "D").status(), "a locked lesson answers nothing");
    }
}
