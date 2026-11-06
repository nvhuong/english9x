package com.english9x.domain.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class ChapterTestAttemptsTest {
    @Test
    public void unlimitedWhenZero() {
        assertTrue(ChapterTestAttempts.canRetake(1000, 0));
    }

    @Test
    public void limitedRetakes() {
        assertTrue(ChapterTestAttempts.canRetake(2, 3));
        assertFalse(ChapterTestAttempts.canRetake(3, 3));
    }

    @Test
    public void bestOfScores() {
        assertEquals(Optional.of(0.9), ChapterTestAttempts.best(List.of(0.4, 0.9, 0.7)));
    }

    @Test
    public void bestOfEmptyIsEmpty() {
        assertEquals(Optional.empty(), ChapterTestAttempts.best(List.of()));
    }
}
