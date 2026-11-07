package com.english9x.workflow.api.events;

import java.util.Optional;
import java.util.UUID;

/**
 * A reviewer approved a version: tell the author ({@code authorId}). Published together with {@link ContentPublished} (which
 * is the one for catalogue refreshes). A bundle publishes one event per distinct author, for that author's first member.
 */
public record ReviewApproved(
        String type, UUID versionId, Optional<UUID> ownerOrgId, String examCode, UUID reviewId, Optional<UUID> bundleId,
        UUID authorId) {}
