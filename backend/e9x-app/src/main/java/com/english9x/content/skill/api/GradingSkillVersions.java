package com.english9x.content.skill.api;

import java.util.Optional;
import java.util.UUID;

/** Grading-skill versions for other modules (the AI grader P4.2, the dry-run, the CMS previews P2.8). Runs in the caller's transaction. */
public interface GradingSkillVersions {

    /** The content type the review workflow knows grading skills by ({@code VersionRef#type}). */
    String REVIEW_TYPE = "grading_skill";

    /**
     * The skill version as the caller may read it (Row-Level Security): the AUTHOR or REVIEWER of the exam sees every status in the skill's context, a TEACHER the
     * PUBLISHED ones, the grader's own login the PUBLISHED ones. Empty otherwise: a learner never reads a skill.
     */
    Optional<GradingSkillVersionView> find(UUID gradingSkillVersionId);
}
