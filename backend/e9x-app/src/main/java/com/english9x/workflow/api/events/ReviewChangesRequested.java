package com.english9x.workflow.api.events;

import java.util.Optional;
import java.util.UUID;

/**
 * A reviewer sent a version back with a comment: tell the author ({@code authorId}). The comment is not part of the event
 * (read it with {@code ReviewService.find}). A bundle publishes one event per distinct author, for that author's first member.
 */
public record ReviewChangesRequested(
        String type, UUID versionId, Optional<UUID> ownerOrgId, String examCode, UUID reviewId, Optional<UUID> bundleId,
        UUID authorId) {}
