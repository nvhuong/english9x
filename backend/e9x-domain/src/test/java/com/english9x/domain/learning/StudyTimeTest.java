package com.english9x.domain.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

public class StudyTimeTest {
    private static final Instant T0 = Instant.parse("2026-11-06T08:00:00Z");

    private static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    @Test
    public void sumsGapsWithinIdle() {
        assertEquals(Duration.ofSeconds(60),
                StudyTime.credited(List.of(at(0), at(30), at(60)), Duration.ofMinutes(2)));
    }

    @Test
    public void ignoresGapsBeyondIdle() {
        assertEquals(Duration.ofSeconds(60),
                StudyTime.credited(List.of(at(0), at(30), at(600), at(630)), Duration.ofMinutes(2)));
    }

    @Test
    public void unsortedInput() {
        assertEquals(Duration.ofSeconds(60),
                StudyTime.credited(List.of(at(60), at(0), at(30)), Duration.ofMinutes(2)));
    }

    @Test
    public void singleBeatIsZero() {
        assertEquals(Duration.ZERO, StudyTime.credited(List.of(at(0)), Duration.ofMinutes(2)));
    }
}
