package com.english9x.content.question.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Makes a new question with its first DRAFT version, exactly as an author's {@code POST /api/cms/questions} does (same checks of the schemas, the
 * files, the sizes; same row-level policies): for the Excel import (P2.7). Joins the caller's transaction, so a caller that makes many drafts in one
 * transaction either keeps all of them or none. Acts as the user bound to the thread ({@code CurrentContextHolder}).
 *
 * <p>Failures are the question endpoint's: {@code AccessDeniedException} (not an AUTHOR of the exam here) and an {@code ErrorResponseException} 422
 * whose {@code errors[]} names the fields.
 */
public interface QuestionDrafts {

    /** @param explanation may be {@code null}; @param points {@code null} means 1 */
    record Draft(String typeCode, JsonNode content, JsonNode answerKey, String explanation, BigDecimal points) {}

    /** @return the id of the new DRAFT version (its {@code question_versions.id}) */
    UUID create(Draft draft);
}
