package com.english9x.course.api;

import java.util.Optional;
import java.util.UUID;

/** Course versions for other modules. Runs in the caller's transaction. */
public interface CourseVersions {

    /** The content type the review workflow knows courses by ({@code VersionRef#type}). */
    String REVIEW_TYPE = "course";

    /** The version of the course in force (PUBLISHED) that the caller may read; empty when there is none. */
    Optional<UUID> publishedVersionOf(UUID courseId);

    /** The course (header) a lesson belongs to; empty when there is no such lesson or the caller may not read it. */
    Optional<UUID> courseOfLesson(UUID lessonId);
}
