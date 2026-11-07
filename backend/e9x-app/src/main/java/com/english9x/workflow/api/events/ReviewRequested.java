package com.english9x.workflow.api.events;

import java.util.Optional;
import java.util.UUID;

/**
 * A version (or a bundle) was submitted for review: tell the REVIEWERs of the exam in the content's context.
 *
 * <p>A standalone submission carries its own review. A bundle publishes one event per distinct exam among its members
 * (a REVIEWER of one exam sees only the members of that exam): {@code bundleId} is set and {@code reviewId},
 * {@code type}, {@code versionId} are those of the first member of that exam.
 */
public record ReviewRequested(
        String type, UUID versionId, Optional<UUID> ownerOrgId, String examCode, UUID reviewId, Optional<UUID> bundleId) {}
