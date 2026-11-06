package com.english9x.domain.learning;

import java.util.List;
import java.util.Map;

public final class UnlockPolicy {
    private UnlockPolicy() {
    }

    /**
     * Lock order is chapter order, then lesson order within the chapter.
     *
     * @throws IllegalArgumentException when {@code lessonId} is not in any chapter
     */
    public static boolean isUnlocked(UnlockRule rule, List<ChapterRef> chapters,
            Map<String, LessonStatus> progress, String lessonId) {
        if (rule == null || chapters == null || progress == null || lessonId == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        boolean found = chapters.stream()
                .flatMap(c -> c.lessons().stream())
                .anyMatch(l -> l.id().equals(lessonId));
        if (!found) {
            throw new IllegalArgumentException("unknown lesson: " + lessonId);
        }
        if (rule == UnlockRule.FREE) {
            return true;
        }
        for (ChapterRef chapter : chapters) {
            for (LessonRef lesson : chapter.lessons()) {
                if (lesson.id().equals(lessonId)) {
                    return true;
                }
                LessonStatus status = progress.get(lesson.id());
                if (status == null || !status.completed()) {
                    return false;
                }
                if (rule == UnlockRule.STRICT && lesson.kind() == LessonKind.CHAPTER_TEST
                        && (status.bestScore() == null || status.bestScore() < chapter.passThreshold())) {
                    return false;
                }
            }
        }
        return true;
    }
}
