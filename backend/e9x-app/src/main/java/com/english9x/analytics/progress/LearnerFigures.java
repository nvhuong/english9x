package com.english9x.analytics.progress;

import com.english9x.analytics.progress.ProgressDtos.CourseProgress;
import com.english9x.analytics.progress.ProgressDtos.PlanCard;
import com.english9x.analytics.progress.ProgressDtos.Progress;
import com.english9x.analytics.progress.ProgressDtos.ScorePoint;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The narrow internal API of the progress screen for the class report (F49): a few figures of ONE learner as the organisation's staff see them. It asks {@link ProgressService#ofLearner}
 * (so the rules of P4.5 are the only ones: the seats the organisation issued, the work done in it, ORG_ADMIN / TEACHER of the context's organisation, a member of it) and
 * summarises its answer; nothing of the progress is computed again here.
 */
@Component
public class LearnerFigures {

    /** One course of the learner: the share of lessons and chapter tests done. */
    public record CourseLine(UUID courseId, String code, String title, int percent) {}

    /** A displayed score; {@code kind} is the grading module's label (PRELIMINARY or OFFICIAL). */
    public record ScoreLine(double value, String kind, String examCode) {}

    public record Figures(Integer percent, List<CourseLine> courses, ScoreLine latestScore, ScoreLine bestScore, int studyMinutes) {}

    private static final LocalDate ALL_TIME = LocalDate.of(2000, 1, 1);

    private final ProgressService service;
    private final ProgressRepository repo;

    LearnerFigures(ProgressService service, ProgressRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    /** 404 / 403 exactly as {@code GET /api/orgs/{id}/learners/{userId}/progress}. {@code percent} is the mean over the learner's courses (null: no course in this organisation). */
    @Transactional(readOnly = true)
    public Figures of(UUID orgId, UUID userId) {
        Progress p = service.ofLearner(orgId, userId);
        Map<UUID, CourseProgress> courses = new LinkedHashMap<>();
        for (PlanCard card : p.plans()) {
            card.courses().forEach(c -> courses.putIfAbsent(c.courseId(), c));
        }
        for (PlanCard card : p.expired()) {
            card.courses().forEach(c -> courses.putIfAbsent(c.courseId(), c));
        }
        List<CourseLine> lines = courses.values().stream().map(c -> new CourseLine(c.courseId(), c.code(), c.title(), c.percent()))
                .sorted(Comparator.comparing(CourseLine::code, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(CourseLine::courseId)).toList();
        Integer percent = lines.isEmpty() ? null : (int) Math.round(lines.stream().mapToInt(CourseLine::percent).average().orElse(0));
        List<ScorePoint> series = p.scoreSeries();
        ScoreLine latest = series.isEmpty() ? null : line(series.get(series.size() - 1));
        ScoreLine best = series.stream().max(Comparator.comparingDouble(ScorePoint::score)).map(LearnerFigures::line).orElse(null);
        int seconds = 0;
        for (Object[] r : repo.study(userId, orgId, ALL_TIME)) {
            seconds += (Integer) r[1];
        }
        return new Figures(percent, lines, latest, best, seconds / 60);
    }

    private static ScoreLine line(ScorePoint s) {
        return new ScoreLine(s.score(), s.kind(), s.examCode());
    }
}
