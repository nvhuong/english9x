package com.english9x.analytics.results;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The JSON of the results screen (F38). */
final class ResultsDtos {

    private ResultsDtos() {}

    /**
     * The score of one skill as shown. {@code label} is PRELIMINARY or OFFICIAL (which stored row is chosen is {@code DisplayedScores}, the rule of the teacher's queue: an
     * OFFICIAL row beats a PRELIMINARY one, a teacher's beats the AI's beats the automatic one) or PENDING when the skill has no score yet (Writing / Speaking still waiting
     * for the AI or a teacher). {@code score} is the converted score, null while there is none (no published table, or pending).
     */
    record SkillScore(String skill, boolean aggregate, Double score, Double raw, Double rawMax, String label, String source) {}

    /** One question of the test with what the learner wrote and the solution side (only ever sent for a scored attempt). */
    record QuestionResult(UUID questionId, String sectionCode, String skill, String typeCode, String typeName, boolean scored, boolean correct, double earned, double max,
            JsonNode answer, JsonNode correctAnswer, String explanation, String transcript) {}

    /** What the AI said about one response of a Writing / Speaking skill (no cost, no token counts). */
    record AiFeedback(UUID questionId, double band, JsonNode result) {}

    /** A teacher's review of a skill. */
    record TeacherFeedback(JsonNode criteria, String comment, double band) {}

    /** The feedback of one Writing / Speaking skill: the criteria and comments the screen shows. */
    record ProductiveFeedback(String skill, List<AiFeedback> ai, TeacherFeedback teacher) {}

    /**
     * The result of an attempt. {@code state}: SCORING while the attempt is submitted but the automatic scoring has not run yet (nothing below the head is sent), READY after
     * (the questions, the solution side and the feedback are filled).
     */
    record AttemptResult(UUID attemptId, String state, String examCode, boolean interrupted, Instant submittedAt, List<SkillScore> skills, List<QuestionResult> questions,
            List<ProductiveFeedback> feedback) {}

    /** The right answers of one question type over the person's attempts. */
    record TypeRate(String typeCode, String typeName, int correct, int total, double rate) {}

    record LessonRef(UUID courseId, String courseTitle, UUID lessonId, String title) {}

    /** A question type the person is weak at and the method lessons, of their courses, that teach it. */
    record Recommendation(String typeCode, String typeName, double rate, List<LessonRef> lessons) {}

    record Weakness(String exam, int attempts, List<TypeRate> types, List<Recommendation> recommendations) {}
}
