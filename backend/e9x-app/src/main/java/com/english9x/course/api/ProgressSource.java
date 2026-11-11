package com.english9x.course.api;

import com.english9x.domain.learning.LessonStatus;
import java.util.Map;
import java.util.UUID;

/**
 * What the lesson gate needs of the learner's progress, implemented by the learning module (P3.2), which owns it. Keys are lesson ids (the ids
 * {@link CourseOutline} reports, as text); a lesson with no entry is not completed. Until a bean exists the gate sees no progress at all.
 */
public interface ProgressSource {

    Map<String, LessonStatus> progress(UUID userId, UUID courseVersionId);
}
