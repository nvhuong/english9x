package com.english9x.domain.learning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class UnlockPolicyTest {
    private final List<ChapterRef> chapters = List.of(
            new ChapterRef("c1", List.of(
                    new LessonRef("l1", LessonKind.LESSON),
                    new LessonRef("l2", LessonKind.LESSON),
                    new LessonRef("t1", LessonKind.CHAPTER_TEST)), 0.7),
            new ChapterRef("c2", List.of(
                    new LessonRef("l3", LessonKind.LESSON),
                    new LessonRef("l4", LessonKind.LESSON)), 0.5));

    private static LessonStatus done(Double score) {
        return new LessonStatus(true, score);
    }

    @Test
    public void freeUnlocksEverything() {
        assertTrue(UnlockPolicy.isUnlocked(UnlockRule.FREE, chapters, Map.of(), "l4"));
    }

    @Test
    public void firstLessonAlwaysUnlocked() {
        assertTrue(UnlockPolicy.isUnlocked(UnlockRule.STRICT, chapters, Map.of(), "l1"));
        assertTrue(UnlockPolicy.isUnlocked(UnlockRule.COMPLETION, chapters, Map.of(), "l1"));
    }

    @Test
    public void completionRequiresPreviousCompleted() {
        Map<String, LessonStatus> p = Map.of("l1", done(null));
        assertTrue(UnlockPolicy.isUnlocked(UnlockRule.COMPLETION, chapters, p, "l2"));
        assertFalse(UnlockPolicy.isUnlocked(UnlockRule.COMPLETION, chapters, p, "t1"));
        assertFalse(UnlockPolicy.isUnlocked(UnlockRule.COMPLETION, chapters, Map.of(), "l2"));
    }

    @Test
    public void completionIgnoresScores() {
        Map<String, LessonStatus> p = Map.of(
                "l1", done(null), "l2", done(null), "t1", done(0.2));
        assertTrue(UnlockPolicy.isUnlocked(UnlockRule.COMPLETION, chapters, p, "l3"));
    }

    @Test
    public void strictRequiresChapterTestPass() {
        Map<String, LessonStatus> low = Map.of(
                "l1", done(null), "l2", done(null), "t1", done(0.6));
        Map<String, LessonStatus> ok = Map.of(
                "l1", done(null), "l2", done(null), "t1", done(0.7));
        assertFalse(UnlockPolicy.isUnlocked(UnlockRule.STRICT, chapters, low, "l3"));
        assertTrue(UnlockPolicy.isUnlocked(UnlockRule.STRICT, chapters, ok, "l3"));
    }

    @Test
    public void unknownLessonRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> UnlockPolicy.isUnlocked(UnlockRule.FREE, chapters, Map.of(), "nope"));
    }
}
