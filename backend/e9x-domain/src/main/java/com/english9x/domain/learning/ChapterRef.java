package com.english9x.domain.learning;

import java.util.List;

/** A chapter with its ordered lessons; {@code passThreshold} is in [0, 1]. */
public record ChapterRef(String id, List<LessonRef> lessons, double passThreshold) {
    public ChapterRef {
        if (id == null || id.isBlank() || lessons == null) {
            throw new IllegalArgumentException("chapter id and lessons are required");
        }
        if (!(passThreshold >= 0.0 && passThreshold <= 1.0)) {
            throw new IllegalArgumentException("passThreshold must be within [0, 1]");
        }
        lessons = List.copyOf(lessons);
    }
}
