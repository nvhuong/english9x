package com.english9x.domain.learning;

public record LessonRef(String id, LessonKind kind) {
    public LessonRef {
        if (id == null || id.isBlank() || kind == null) {
            throw new IllegalArgumentException("lesson id and kind are required");
        }
    }
}
