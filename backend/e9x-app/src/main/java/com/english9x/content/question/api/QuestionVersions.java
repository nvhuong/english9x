package com.english9x.content.question.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Question versions for other modules. Every method runs in the caller's transaction (it joins it, or opens one), so Row-Level Security answers for
 * the bound user and organisation.
 */
public interface QuestionVersions {

    /** The content type the review workflow knows questions by ({@code VersionRef#type}, {@code review_requests.content_type}). */
    String REVIEW_TYPE = "question";

    /**
     * The learner-safe view of a PUBLISHED or ARCHIVED version of English9x or of the current organisation (an attempt that began on a version keeps
     * reading it after a newer one is published). Empty for a draft, a version under review, another organisation's, or one that does not exist.
     * Whether this person may sit this question is the caller's rule: this method only guarantees that no answer-side field leaves.
     */
    Optional<QuestionExamView> examView(UUID questionVersionId);

    /** The staff view of a version, answer key included. Empty unless the caller is an AUTHOR or REVIEWER of its exam in its context. */
    Optional<QuestionVersionView> find(UUID questionVersionId);

    /** Every version of a question, oldest first, answer key included; same rule as {@link #find}. */
    List<QuestionVersionView> versions(UUID questionId);
}
