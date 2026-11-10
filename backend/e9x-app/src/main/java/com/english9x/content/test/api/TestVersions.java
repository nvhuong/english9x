package com.english9x.content.test.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Test versions for other modules (the exam room P3.3, the CMS previews P2.8, the comparison P2.9). Every method runs in the caller's transaction (it joins
 * it, or opens one), so Row-Level Security answers for the bound user and organisation.
 */
public interface TestVersions {

    /** The content type the review workflow knows tests by ({@code VersionRef#type}, {@code review_requests.content_type}). */
    String REVIEW_TYPE = "test";

    /**
     * What the exam room shows of a test version: the parts in order and, in each, the groups (stimulus) with their questions (content side), for a
     * PUBLISHED or ARCHIVED version of English9x or of the current organisation (an attempt that began on a version keeps reading it after a newer one
     * replaced it). Empty for a draft or a version under review (staff may preview those through the CMS, never through here), for another organisation's
     * test, for a version that does not exist, and when any group or question of it is not servable. By construction of {@link TestExamView} nothing of
     * the solution side (answer key, transcript, sample answer, notes, explanation) can be in the answer. Whether this person may sit this test (an
     * entitlement, an attempt) is the caller's rule.
     */
    Optional<TestExamView> forExamRoom(UUID testVersionId);

    /** The structure of a version in any status: empty unless the caller is the AUTHOR or REVIEWER of the exam in its context, or the version is PUBLISHED / ARCHIVED and in the caller's context. */
    Optional<TestStaffView> find(UUID testVersionId);

    /** The test a version belongs to; the key of the overrides (score tables, grading policies of TEST scope). Same visibility as {@link #find}. */
    Optional<UUID> testOf(UUID testVersionId);
}
