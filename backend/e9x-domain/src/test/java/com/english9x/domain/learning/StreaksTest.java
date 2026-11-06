package com.english9x.domain.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

public class StreaksTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    private static DailyActivity day(int back, int minutes, int lessons) {
        return new DailyActivity(TODAY.minusDays(back), minutes, lessons);
    }

    @Test
    public void countsConsecutiveDaysEndingToday() {
        assertEquals(3, Streaks.current(List.of(day(0, 10, 0), day(1, 12, 0), day(2, 20, 0)), TODAY, 10));
    }

    @Test
    public void todayNotYetQualifiedCountsFromYesterday() {
        assertEquals(2, Streaks.current(List.of(day(0, 3, 0), day(1, 12, 0), day(2, 20, 0)), TODAY, 10));
    }

    @Test
    public void gapBreaksStreak() {
        assertEquals(1, Streaks.current(List.of(day(0, 10, 0), day(2, 20, 0), day(3, 20, 0)), TODAY, 10));
    }

    @Test
    public void lessonCompletionQualifiesShortDay() {
        assertEquals(2, Streaks.current(List.of(day(0, 1, 1), day(1, 10, 0)), TODAY, 10));
    }

    @Test
    public void noActivityIsZero() {
        assertEquals(0, Streaks.current(List.of(), TODAY, 10));
        assertEquals(0, Streaks.current(List.of(day(2, 50, 0)), TODAY, 10));
    }
}
