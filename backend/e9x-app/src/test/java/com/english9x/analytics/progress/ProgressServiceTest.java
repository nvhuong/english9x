package com.english9x.analytics.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.english9x.analytics.progress.ProgressDtos.Score;
import com.english9x.analytics.progress.ProgressDtos.ScorePoint;
import com.english9x.analytics.progress.ProgressRepository.Row;
import com.english9x.grading.api.DisplayedScores.Candidate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProgressServiceTest {

    private static Row row(UUID id, String exam, long day, String skill, String kind, String source, double v) {
        return new Row(id, null, exam, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(day * 86400), new Candidate(skill, kind, source, v));
    }

    @Test
    void predictedScoreIsTheMeanOfTheLastThreeOfTheSameExamAndTheTeacherScoreWins() {
        List<Row> rows = new ArrayList<>();
        double[] totals = {500, 600, 700, 800};
        UUID first = UUID.randomUUID();
        for (int i = 0; i < totals.length; i++) {
            UUID id = i == 0 ? first : UUID.randomUUID();
            rows.add(row(id, "TOEIC", i, "TOTAL", "OFFICIAL", "AUTO", totals[i]));
        }
        rows.add(row(UUID.randomUUID(), "IELTS", 1, "OVERALL", "PRELIMINARY", "AI", 6.0));
        // a teacher's official overall replaces the AI's preliminary one on the same attempt
        UUID ielts = UUID.randomUUID();
        rows.add(row(ielts, "IELTS", 2, "OVERALL", "PRELIMINARY", "AI", 5.0));
        rows.add(row(ielts, "IELTS", 2, "OVERALL", "OFFICIAL", "TEACHER", 7.0));
        List<ScorePoint> s = ProgressService.series(ProgressService.attempts(rows).values());
        List<ScorePoint> toeic = s.stream().filter(p -> p.examCode().equals("TOEIC")).toList();
        assertEquals(List.of(500.0, 550.0, 600.0, 700.0), toeic.stream().map(ScorePoint::predicted).toList());
        List<ScorePoint> ielts2 = s.stream().filter(p -> p.examCode().equals("IELTS")).toList();
        assertEquals(2, ielts2.size());
        assertEquals("PRELIMINARY", ielts2.get(0).kind());
        assertEquals("OFFICIAL", ielts2.get(1).kind());
        assertEquals(7.0, ielts2.get(1).score());
        assertEquals(6.5, ielts2.get(1).predicted());
        Score none = ProgressService.attempts(List.of(new Row(UUID.randomUUID(), null, "TOEIC", Instant.EPOCH, new Candidate("LISTENING", "OFFICIAL", "AUTO", 300)))).values().iterator().next().score();
        assertEquals(null, none, "an attempt with only skill scores has no aggregate");
    }
}
