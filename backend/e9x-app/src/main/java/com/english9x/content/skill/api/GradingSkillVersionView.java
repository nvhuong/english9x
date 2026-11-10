package com.english9x.content.skill.api;

import com.english9x.workflow.api.ContentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A version of a grading skill. {@code criteria} is a JSON array of {@code {code, name, min, max, step}}, {@code samples} a JSON array of
 * {@code {band, text | mediaId, commentary}}, {@code resultSchema} the JSON Schema (2020-12) the AI's answer must match; its {@code properties} name every criterion code.
 *
 * @param ownerOrgId empty = English9x
 */
public record GradingSkillVersionView(UUID versionId, UUID skillId, int versionNo, ContentStatus status, Optional<UUID> ownerOrgId, String examCode,
                                      String questionTypeCode, String rubric, JsonNode criteria, String promptTemplate, JsonNode samples, JsonNode resultSchema,
                                      UUID createdBy, Instant createdAt, Instant updatedAt) {}
