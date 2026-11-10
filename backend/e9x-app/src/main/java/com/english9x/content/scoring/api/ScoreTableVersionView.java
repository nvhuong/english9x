package com.english9x.content.scoring.api;

import com.english9x.workflow.api.ContentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A version of a score table as the staff of its exam see it.
 *
 * @param ownerOrgId empty = English9x
 * @param testId the TEST (not a test version) of an override; empty for the exam default
 * @param function TABLE, or the aggregate function of the domain (IELTS_OVERALL, IELTS_WRITING_WEIGHTED, SUM), whose version has no rows
 */
public record ScoreTableVersionView(UUID versionId, UUID tableId, int versionNo, ContentStatus status, Optional<UUID> ownerOrgId, String examCode,
                                    String skill, ScoreScope scope, Optional<UUID> testId, String function, List<ScoreRow> rows, UUID createdBy,
                                    Instant createdAt, Instant updatedAt) {}
