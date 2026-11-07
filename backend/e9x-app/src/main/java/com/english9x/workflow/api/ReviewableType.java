package com.english9x.workflow.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The port a content module implements, once per content type, as a Spring bean. {@link ReviewService} calls it
 * <strong>inside its own transaction</strong> (same connection, so the same Row-Level Security context), so an
 * implementation must use the application's JdbcTemplate / EntityManager and must not open a transaction of its own
 * ({@code REQUIRES_NEW}).
 *
 * <p>The locks are part of the contract: they are what makes an approval hold against concurrent changes.
 */
public interface ReviewableType {

    /** Lower-case name of the content type, {@code [a-z][a-z0-9_]{0,63}}; stored in {@code review_requests.content_type}. */
    String type();

    /**
     * Reads the version and locks its row ({@code SELECT ... FOR UPDATE}) until the surrounding transaction ends.
     * Empty when the version does not exist or the caller's Row-Level Security does not show it.
     */
    Optional<Reviewable> lockForReview(UUID versionId);

    /**
     * The current status of a version this one depends on, read with a shared lock ({@code SELECT status ... FOR SHARE}):
     * nobody can archive or change it until the surrounding transaction ends. Empty when it does not exist or is not visible.
     */
    Optional<ContentStatus> lockStatus(UUID versionId);

    /**
     * Writes the new status of a version whose row {@link #lockForReview} locked in this transaction. The database trigger
     * {@code e9x_guard_version_lifecycle()} refuses moves the lifecycle does not allow.
     */
    void setStatus(UUID versionId, ContentStatus status);
}
