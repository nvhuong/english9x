package com.english9x.workflow.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One line of the review queue: a standalone review ({@code reviewId} set, size 1) or a whole bundle ({@code bundleId} set,
 * {@code size} = its open members the caller may see). {@code type}, {@code examCode}, {@code ownerOrgId} and
 * {@code submittedBy} are those of the oldest visible member; {@code submittedAt} is the oldest submission time.
 */
public record ReviewQueueEntry(
        Optional<UUID> reviewId, Optional<UUID> bundleId, int size, String type, String examCode,
        Optional<UUID> ownerOrgId, UUID submittedBy, Instant submittedAt) {}
