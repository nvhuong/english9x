package com.english9x.content.strategy.api;

import com.english9x.workflow.api.ContentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** @param ownerOrgId empty = English9x content */
public record StrategyLessonVersionView(UUID versionId, UUID lessonId, int versionNo, ContentStatus status, Optional<UUID> ownerOrgId, String examCode,
                                        String title, JsonNode blocks, List<String> questionTypeCodes, UUID createdBy, Instant createdAt, Instant updatedAt) {}
