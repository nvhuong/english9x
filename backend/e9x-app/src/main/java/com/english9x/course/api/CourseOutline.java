package com.english9x.course.api;

import com.english9x.domain.learning.ChapterRef;
import java.util.List;
import java.util.UUID;

/** The shape of a course version for the learning and exam modules. Runs in the caller's transaction: row-level security decides which versions it may read. */
public interface CourseOutline {

    /**
     * The chapters of the course version in order, each with its lessons in order. Ids are the ids of the {@code course_chapters} and {@code course_lessons}
     * rows (as text); {@code passThreshold} is the chapter's, in [0, 1]. Empty when the version does not exist or the caller may not read it.
     */
    List<ChapterRef> lessonsInOrder(UUID courseVersionId);
}
