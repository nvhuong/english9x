package com.english9x.domain.exam;

import java.time.Duration;

public record SectionPlan(String id, Duration duration) {
    public SectionPlan {
        if (id == null || id.isBlank() || duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("section needs an id and a positive duration");
        }
    }
}
