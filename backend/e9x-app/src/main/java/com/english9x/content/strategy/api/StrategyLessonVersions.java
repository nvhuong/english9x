package com.english9x.content.strategy.api;

import java.util.Optional;
import java.util.UUID;

/** Strategy lesson versions for other modules (courses P3.1, the lesson page P3.2, previews P2.8). Runs in the caller's transaction. */
public interface StrategyLessonVersions {

    /** The content type the review workflow knows lessons by ({@code VersionRef#type}). */
    String REVIEW_TYPE = "strategy_lesson";

    /** What a learner may read of a PUBLISHED or ARCHIVED version of English9x or of the current organisation; empty otherwise. Entitlement is the caller's rule. */
    Optional<StrategyLessonExamView> examView(UUID lessonVersionId);

    /** The staff view of any status. Empty unless the caller is an AUTHOR or REVIEWER of the exam in the version's context. */
    Optional<StrategyLessonVersionView> find(UUID lessonVersionId);
}
