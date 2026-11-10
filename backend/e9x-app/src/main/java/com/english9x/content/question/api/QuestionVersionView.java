package com.english9x.content.question.api;

import com.english9x.workflow.api.ContentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A question version with its solution side ({@code answerKey}, {@code explanation}), for the people who may read the key: the AUTHOR or REVIEWER
 * of the exam in the version's context. Never serialise this type to a learner.
 *
 * @param ownerOrgId empty = English9x content
 */
public record QuestionVersionView(UUID versionId, UUID questionId, int versionNo, ContentStatus status, Optional<UUID> ownerOrgId, String examCode,
                                  String typeCode, JsonNode content, JsonNode answerKey, String explanation, BigDecimal points, UUID createdBy,
                                  Instant createdAt, Instant updatedAt) {}
