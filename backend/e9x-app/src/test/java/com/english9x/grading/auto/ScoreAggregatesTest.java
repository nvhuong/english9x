package com.english9x.grading.auto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ScoreAggregatesTest {

    @Test
    void toeicTotalIsTheSumOfTheTwoSections() {
        assertEquals(990, ScoreAggregates.toeicTotal(Map.of("LISTENING", 495.0, "READING", 495.0)).getAsDouble());
        assertEquals(10, ScoreAggregates.of("TOEIC", Map.of("LISTENING", 5.0, "READING", 5.0)).getAsDouble());
        assertTrue(ScoreAggregates.toeicTotal(Map.of("LISTENING", 495.0)).isEmpty(), "none while a section is missing");
        assertTrue(ScoreAggregates.toeicTotal(Map.of("LISTENING", 7.0, "READING", 5.0)).isEmpty(), "none from a score the domain refuses (not a step of 5)");
        assertTrue(ScoreAggregates.toeicTotal(Map.of("LISTENING", 500.0, "READING", 5.0)).isEmpty(), "nor from a score above 495");
    }

    @Test
    void ieltsOverallNeedsAllFourSkillsAndRoundsToTheHalfBand() {
        assertTrue(ScoreAggregates.ieltsOverall(Map.of("LISTENING", 6.5, "READING", 7.0, "WRITING", 6.0)).isEmpty(), "Writing and Speaking come later");
        assertEquals(6.5, ScoreAggregates.ieltsOverall(Map.of("LISTENING", 6.5, "READING", 6.5, "WRITING", 6.0, "SPEAKING", 6.5)).getAsDouble(), "avg 6.375 rounds to 6.5");
        assertEquals(7.0, ScoreAggregates.of("IELTS", Map.of("LISTENING", 7.0, "READING", 7.0, "WRITING", 6.5, "SPEAKING", 6.5)).getAsDouble(), "avg 6.75 rounds up to 7");
        assertTrue(ScoreAggregates.ieltsOverall(Map.of("LISTENING", 6.3, "READING", 7.0, "WRITING", 6.0, "SPEAKING", 6.0)).isEmpty(), "not from a band that is not a half band");
    }

    @Test
    void examsKeepTheirOwnAggregate() {
        assertEquals("TOTAL", ScoreAggregates.aggregateSkill("TOEIC"));
        assertEquals("OVERALL", ScoreAggregates.aggregateSkill("IELTS"));
        assertEquals(null, ScoreAggregates.aggregateSkill("OTHER"));
        assertTrue(ScoreAggregates.of("OTHER", Map.of()).isEmpty());
    }

    @Test
    void scoreSkillOfIeltsReadingFollowsTheVariant() {
        assertEquals("READING_AC", AutoGrader.scoreSkill("IELTS", "READING", "AC"));
        assertEquals("READING_GT", AutoGrader.scoreSkill("IELTS", "READING", "GT"));
        assertEquals(null, AutoGrader.scoreSkill("IELTS", "READING", null), "no variant, no table: never a guess");
        assertEquals("LISTENING", AutoGrader.scoreSkill("IELTS", "LISTENING", "AC"));
        assertEquals("READING", AutoGrader.scoreSkill("TOEIC", "READING", null));
    }
}
