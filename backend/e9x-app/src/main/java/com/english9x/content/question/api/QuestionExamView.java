package com.english9x.content.question.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a learner may see of a question version: its content (what is asked) and its points. By construction there is no field for the answer
 * key, the explanation, the transcript or a sample answer: they live on the solution side of the version and this type is built from the
 * {@code e9x_question_exam_view} function, which does not return them. Do not add one; add a separate type for a post-submit review view.
 */
public record QuestionExamView(UUID versionId, UUID questionId, String examCode, String typeCode, JsonNode content, BigDecimal points) {}
