package com.english9x.content.question.api;

import java.util.Optional;
import java.util.UUID;

/** Question group versions for other modules (tests, P2.6; the exam room, P3.3). Same transaction and Row-Level Security rules as {@link QuestionVersions}. */
public interface QuestionGroupVersions {

    /** The content type the review workflow knows groups by. A test lists its group versions as dependencies of this type. */
    String REVIEW_TYPE = "question_group";

    /** The learner-safe view of a PUBLISHED or ARCHIVED group version of English9x or of the current organisation: stimulus and member ids, no solution. */
    Optional<QuestionGroupExamView> examView(UUID groupVersionId);

    /** The staff view, solution side included; empty unless the caller is an AUTHOR or REVIEWER of the exam in the version's context. */
    Optional<QuestionGroupVersionView> find(UUID groupVersionId);
}
