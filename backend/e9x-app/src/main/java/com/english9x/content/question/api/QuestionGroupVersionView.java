package com.english9x.content.question.api;

import com.english9x.workflow.api.ContentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A group version with its solution side ({@code solution}: transcript, notes) and its member question versions in order. Staff side only. */
public record QuestionGroupVersionView(UUID versionId, UUID groupId, int versionNo, ContentStatus status, Optional<UUID> ownerOrgId, String examCode,
                                       JsonNode content, JsonNode solution, List<UUID> questionVersionIds, UUID createdBy, Instant createdAt,
                                       Instant updatedAt) {}
