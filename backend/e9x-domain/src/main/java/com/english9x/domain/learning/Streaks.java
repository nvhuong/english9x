package com.english9x.domain.learning;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Streaks {
    private Streaks() {
    }

    /**
     * Consecutive qualifying days ending today (if today qualifies) or yesterday.
     * A day qualifies with at least {@code minMinutes} minutes or one completed lesson.
     */
    public static int current(List<DailyActivity> days, LocalDate today, int minMinutes) {
        if (days == null || today == null || minMinutes < 0) {
            throw new IllegalArgumentException("invalid streak arguments");
        }
        Set<LocalDate> qualified = new HashSet<>();
        for (DailyActivity d : days) {
            if (d.minutes() >= minMinutes || d.lessonsCompleted() >= 1) {
                qualified.add(d.date());
            }
        }
        LocalDate cursor = qualified.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (qualified.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }
}
