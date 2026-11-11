package com.english9x.grading.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.grading.api.DisplayedScores.Candidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DisplayedScoresTest {

    private static Candidate row(String skill, String kind, String source, double scaled) {
        return new Candidate(skill, kind, source, scaled);
    }

    @Test
    void theTeachersOfficialScoreReplacesTheAiPreliminaryOne() {
        Map<String, Candidate> shown = DisplayedScores.select(List.of(row("WRITING", "PRELIMINARY", "AI", 6.5), row("WRITING", "OFFICIAL", "TEACHER", 7.5)));
        assertEquals(7.5, shown.get("WRITING").scaled());
        assertEquals("TEACHER", shown.get("WRITING").source());
        assertTrue(shown.get("WRITING").isOfficial());
    }

    @Test
    void theAiScoreShowsAsPreliminaryUntilATeacherGrades() {
        Candidate ai = DisplayedScores.select(List.of(row("SPEAKING", "PRELIMINARY", "AI", 6.0))).get("SPEAKING");
        assertEquals(6.0, ai.scaled());
        assertFalse(ai.isOfficial());
    }

    @Test
    void officialBeatsPreliminaryWhateverTheOrderOfTheRows() {
        Map<String, Candidate> shown = DisplayedScores.select(List.of(row("WRITING", "OFFICIAL", "TEACHER", 7.0), row("WRITING", "PRELIMINARY", "AI", 9.0), row("READING", "OFFICIAL", "AUTO", 6.0)));
        assertEquals(7.0, shown.get("WRITING").scaled());
        assertEquals(6.0, shown.get("READING").scaled());
        assertEquals(2, shown.size());
    }

    @Test
    void aTeacherBeatsTheAiAmongOfficialRows() {
        Map<String, Candidate> shown = DisplayedScores.select(List.of(row("WRITING", "OFFICIAL", "AI", 6.0), row("WRITING", "OFFICIAL", "TEACHER", 7.0)));
        assertEquals("TEACHER", shown.get("WRITING").source());
    }
}
