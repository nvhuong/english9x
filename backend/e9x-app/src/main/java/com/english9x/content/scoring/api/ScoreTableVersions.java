package com.english9x.content.scoring.api;

import java.util.Optional;
import java.util.UUID;

/** Score-table versions for other modules (the test builder P2.6, the CMS previews P2.8). Runs in the caller's transaction. */
public interface ScoreTableVersions {

    /** The content type the review workflow knows score tables by ({@code VersionRef#type}). */
    String REVIEW_TYPE = "score_table";

    /** The staff view of any status. Empty unless the caller is an AUTHOR or REVIEWER of the exam in the version's context (a PUBLISHED or ARCHIVED one is public). */
    Optional<ScoreTableVersionView> find(UUID scoreTableVersionId);
}
