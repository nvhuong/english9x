package com.english9x.domain.learning;

import java.time.LocalDate;

public record DailyActivity(LocalDate date, int minutes, int lessonsCompleted) {
    public DailyActivity {
        if (date == null || minutes < 0 || lessonsCompleted < 0) {
            throw new IllegalArgumentException("invalid daily activity");
        }
    }
}
