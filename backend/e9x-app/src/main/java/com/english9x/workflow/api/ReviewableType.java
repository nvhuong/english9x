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
 *
 * <p>Row-Level Security of the version table must let a REVIEWER of the exam, in the content's context, both read and
 * <em>lock</em> (SELECT and UPDATE policy) the version row: {@code lockForReview} returns nothing otherwise, and the
 * review fails as "the version no longer exists". An AUTHOR-only UPDATE policy is therefore wrong.
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
     * The status of a version this one depends on, read with a shared lock ({@code SELECT status ... FOR SHARE}): nobody can
     * archive or change it until the surrounding transaction ends. Empty when it does not exist, is not visible, or the
     * caller may not lock it: {@code FOR SHARE} also applies the table's UPDATE policy, which an organisation never has on
     * English9x rows. For that case the service falls back to {@link #readStatus}.
     */
    Optional<ContentStatus> lockStatus(UUID versionId);

    /**
     * Plain {@code SELECT status, owner_org_id} (no lock; obeys only the SELECT policy). The service accepts a dependency read
     * this way only when it is PUBLISHED and owned by another context than the current one (an organisation using published
     * English9x content). Accepted cost: such a dependency is not locked, so one archived at the very same instant does not
     * block the approval. Empty when it does not exist or is not readable by the caller.
     */
    Optional<VersionStatus> readStatus(UUID versionId);

    /** Status and owner (empty = English9x) of a version row. */
    record VersionStatus(ContentStatus status, Optional<UUID> ownerOrgId) {}

    /**
     * Writes the new status of a version whose row {@link #lockForReview} locked in this transaction. The database trigger
     * {@code e9x_guard_version_lifecycle()} refuses moves the lifecycle does not allow.
     */
    void setStatus(UUID versionId, ContentStatus status);
}
