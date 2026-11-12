package com.english9x.grading.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The solution side of one SCORED attempt, for the result page of its owner (F38). The answer key is the grader's side of the database (the request role cannot read it), so
 * this is the only way out of it, and it is narrow: it answers only for an attempt that is SUBMITTED and has been scored by the automatic scoring (the late-sync grace is over,
 * so no answer can still arrive and nobody can tune an answer to the key), of exactly the person and organisation the caller names (the row proves it: a forged pair finds
 * nothing), and says nothing for any other attempt. The caller decides who may ask (the owner).
 */
public interface AttemptSolutions {

    /**
     * One question of the attempt's test. {@code scored} is false for a question a teacher or the AI scores (Writing, Speaking): it has no right or wrong here ({@code correct}
     * false, {@code earned} 0), and {@code correctAnswer} is null. {@code correctAnswer} is the accepted variants per blank ({@code [[...],[...]]}), {@code answer} what the learner
     * wrote (null = none), {@code transcript} the review material of the key when it has one.
     */
    record Item(UUID questionId, UUID questionVersionId, String sectionCode, String skill, String typeCode, boolean scored, boolean correct, double earned, double max,
            JsonNode answer, JsonNode correctAnswer, String explanation, String transcript) {}

    /** Empty when there is no such SUBMITTED and scored attempt of that person and organisation. */
    Optional<List<Item>> of(UUID attemptId, UUID userId, UUID orgId);
}
