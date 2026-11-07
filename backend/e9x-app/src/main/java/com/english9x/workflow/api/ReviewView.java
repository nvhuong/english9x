package com.english9x.workflow.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A review as the caller may see it. {@code status} is IN_REVIEW while open, then CHANGES_REQUESTED or PUBLISHED (final).
 * {@code ownerOrgId} empty = an English9x review.
 */
public record ReviewView(
        UUID id,
        Optional<UUID> bundleId,
        String type,
        UUID versionId,
        Optional<UUID> ownerOrgId,
        String examCode,
        UUID authorId,
        UUID submittedBy,
        Instant submittedAt,
        ContentStatus status,
        Optional<UUID> reviewerId,
        Optional<Instant> decidedAt,
        Optional<String> comment) {}
