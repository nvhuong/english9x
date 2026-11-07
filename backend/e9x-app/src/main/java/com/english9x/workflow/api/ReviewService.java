package com.english9x.workflow.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The review workflow (spec §7.1). Every method acts as the signed-in user in the bound context
 * ({@code CurrentContextHolder}): it must be called in the request thread, and nothing about who is acting comes from an
 * argument. Every method runs in one transaction (it joins the caller's, or starts its own) and either completes or
 * changes nothing.
 *
 * <p>Failures: {@link org.springframework.security.access.AccessDeniedException} (403: the role is missing for this exam in
 * this context, or {@link SelfReviewException}); {@link ReviewNotFoundException} (404: no such version or review, or not
 * one the caller may see, indistinguishably); {@link ReviewConflictException} (409: the state is not the one the action
 * needs, including losing a race); {@link UnpublishedDependencyException} (409, lists the dependencies);
 * {@link InvalidReviewInputException} (400). All but the first are {@code ErrorResponseException}s: the problem+json
 * advice renders them, no controller code is needed.
 *
 * <p>Events, published last in the transaction: {@code ReviewRequested} on submit, {@code ReviewChangesRequested} and
 * {@code ReviewApproved} (with the author) on decisions, {@code ContentPublished} on approval.
 */
public interface ReviewService {

    /**
     * Submits a version for review (DRAFT or CHANGES_REQUESTED to IN_REVIEW). The caller must be an AUTHOR of the version's
     * exam in the version's own context. The author recorded is the version's creator, the submitter is the caller.
     *
     * @return the id of the new review
     */
    UUID submit(VersionRef version);

    /** Submits several versions as one bundle: all are submitted or none. @return the id of the bundle */
    UUID submit(BundleRef bundle);

    /**
     * A REVIEWER of the exam returns the version to its author with a comment (the version becomes CHANGES_REQUESTED, editable
     * again; submit it again to get a new review). The comment is required.
     */
    void requestChanges(UUID reviewId, String comment);

    /** Same for every member of a bundle: all or none. */
    void requestBundleChanges(UUID bundleId, String comment);

    /**
     * A REVIEWER of the exam publishes the version (IN_REVIEW to PUBLISHED) and {@code ContentPublished} is published in
     * the same transaction, once. Refused when the reviewer wrote or submitted the version, and with
     * {@link UnpublishedDependencyException} while a dependency is not PUBLISHED.
     */
    void approve(UUID reviewId);

    /**
     * Publishes every member of a bundle, all in this one transaction: a member that cannot be approved (unpublished
     * dependency, own version, missing role) means none is. Dependencies between members are satisfied in dependency order.
     */
    void approveBundle(UUID bundleId);

    Optional<ReviewView> find(UUID reviewId);

    /**
     * The open queue the caller may see (a reviewer's queue, an author's pending submissions), oldest first: one entry per
     * bundle and one per standalone review, so a 2000-member import is one line. {@code limit} is clamped to 1..200.
     */
    List<ReviewQueueEntry> open(int offset, int limit);

    /** The reviews of a bundle the caller may see, oldest id first; {@code limit} is clamped to 1..200. */
    List<ReviewView> members(UUID bundleId, int offset, int limit);
}
