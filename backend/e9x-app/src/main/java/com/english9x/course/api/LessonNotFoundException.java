package com.english9x.course.api;

import java.util.UUID;

/** The lesson does not exist, or the caller may not read its course version (a 404 for the caller). */
public class LessonNotFoundException extends RuntimeException {
    public LessonNotFoundException(UUID lessonId) {
        super("no such lesson: " + lessonId);
    }
}
