package com.english9x.domain.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class OfficialScoreTest {

    private static final Instant T = Instant.parse("2026-11-06T00:00:00Z");
    private static final ScoreRecord AI = new ScoreRecord("AI", 6.0, T);
    private static final ScoreRecord TEACHER = new ScoreRecord("TEACHER", 7.0, T);

    @Test
    public void aiModeAiIsOfficial() {
        DisplayedScore d = OfficialScore.select(GradingMode.AI, Optional.of(AI), Optional.empty()).orElseThrow();
        assertEquals(AI, d.score());
        assertEquals(ScoreKind.OFFICIAL, d.kind());
    }

    @Test
    public void aiModeTeacherScoreOverridesAi() {
        DisplayedScore d = OfficialScore.select(GradingMode.AI, Optional.of(AI), Optional.of(TEACHER)).orElseThrow();
        assertEquals(TEACHER, d.score());
        assertEquals(ScoreKind.OFFICIAL, d.kind());
    }

    @Test
    public void teacherScoreIsOfficialInEveryMode() {
        for (GradingMode m : GradingMode.values()) {
            DisplayedScore d = OfficialScore.select(m, Optional.empty(), Optional.of(TEACHER)).orElseThrow();
            assertEquals(TEACHER, d.score(), m.name());
            assertEquals(ScoreKind.OFFICIAL, d.kind(), m.name());
        }
    }

    @Test
    public void teacherModeIgnoresAi() {
        assertTrue(OfficialScore.select(GradingMode.TEACHER, Optional.of(AI), Optional.empty()).isEmpty());
        DisplayedScore d = OfficialScore.select(GradingMode.TEACHER, Optional.of(AI), Optional.of(TEACHER)).orElseThrow();
        assertEquals(TEACHER, d.score());
        assertEquals(ScoreKind.OFFICIAL, d.kind());
    }

    @Test
    public void bothModeAiIsPreliminaryUntilTeacher() {
        DisplayedScore d = OfficialScore.select(GradingMode.BOTH, Optional.of(AI), Optional.empty()).orElseThrow();
        assertEquals(AI, d.score());
        assertEquals(ScoreKind.PRELIMINARY, d.kind());
    }

    @Test
    public void bothModeTeacherReplacesAi() {
        DisplayedScore d = OfficialScore.select(GradingMode.BOTH, Optional.of(AI), Optional.of(TEACHER)).orElseThrow();
        assertEquals(TEACHER, d.score());
        assertEquals(ScoreKind.OFFICIAL, d.kind());
    }

    @Test
    public void noScoreYieldsEmpty() {
        for (GradingMode m : GradingMode.values()) {
            assertTrue(OfficialScore.select(m, Optional.empty(), Optional.empty()).isEmpty());
        }
    }
}
