package com.english9x.analytics.progress;

import com.english9x.analytics.progress.ProgressDtos.CourseProgress;
import com.english9x.analytics.progress.ProgressDtos.PendingAssignment;
import com.english9x.analytics.progress.ProgressDtos.PlanCard;
import com.english9x.analytics.progress.ProgressDtos.Progress;
import com.english9x.analytics.progress.ProgressDtos.Score;
import com.english9x.analytics.progress.ProgressDtos.ScorePoint;
import com.english9x.analytics.progress.ProgressDtos.Streak;
import com.english9x.analytics.progress.ProgressDtos.WeekMinutes;
import com.english9x.analytics.progress.ProgressRepository.Course;
import com.english9x.analytics.progress.ProgressRepository.Pending;
import com.english9x.analytics.progress.ProgressRepository.Plan;
import com.english9x.analytics.progress.ProgressRepository.Row;
import com.english9x.grading.api.DisplayedScores;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Learner progress by plan (F24). It READS what the learning, exam, grading and classroom modules stored and computes nothing of theirs again: the displayed score of an attempt
 * is the one {@link DisplayedScores} picks (so PRELIMINARY / OFFICIAL is the grading module's label).
 *
 * <p><b>Plans, not courses.</b> The entitlements are grouped by where they come from: one card per order (ORDER) and one per seat (SEAT). A plan whose every entitlement has ended
 * is in {@code expired}: its figures are shown (read only, no "continue" lesson). Progress is stored per person, course and lesson version and not per entitlement, so a course
 * bought again continues where it stopped.
 *
 * <p><b>Predicted score.</b> For each scored attempt, the aggregate score shown (OVERALL for IELTS, TOTAL for TOEIC) averaged with the two scored attempts of the same exam
 * before it (fewer at the start). Deliberately simple: a moving average of the last three results.
 */
@Service
class ProgressService {

    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int WEEKS = 8;
    static final int PREDICT_WINDOW = 3;

    private final ProgressRepository repo;
    private final Authz authz;
    private final Clock clock;

    ProgressService(ProgressRepository repo, Authz authz, Clock clock) {
        this.repo = repo;
        this.authz = authz;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Progress mine() {
        UUID me = CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no user")).userId();
        return build(me, null);
    }

    /** The organisation must be the one of the context (any other id: 404); outside any organisation, or without ORG_ADMIN / TEACHER there: 403; a non-member: 404. */
    @Transactional(readOnly = true)
    public Progress ofLearner(UUID orgId, UUID userId) {
        Optional<UUID> ctx = CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no user")).orgId();
        if (ctx.isEmpty()) {
            throw new AccessDeniedException("an organisation context is needed");
        }
        if (!ctx.get().equals(orgId)) {
            throw ProgressProblems.notFound();
        }
        if (!authz.has(Role.ORG_ADMIN, Optional.empty()) && !authz.has(Role.TEACHER, Optional.empty())) {
            throw new AccessDeniedException("organisation staff only");
        }
        if (!repo.viewable(userId, orgId)) {
            throw ProgressProblems.notFound();
        }
        return build(userId, orgId);
    }

    private Progress build(UUID user, UUID org) {
        Instant now = clock.instant();
        Map<UUID, Course> courses = new HashMap<>();
        repo.courses(user, org).forEach(c -> courses.put(c.courseId(), c));
        List<Row> rows = repo.attempts(user, org);
        Map<UUID, Attempt> attempts = attempts(rows);
        List<Pending> pending = repo.pending(user, org);

        Map<String, List<Plan>> groups = new LinkedHashMap<>();
        for (Plan p : repo.plans(user, org)) {
            groups.computeIfAbsent(p.source() + "/" + p.groupId(), k -> new ArrayList<>()).add(p);
        }
        List<PlanCard> plans = new ArrayList<>();
        List<PlanCard> expired = new ArrayList<>();
        for (List<Plan> g : groups.values()) {
            boolean ended = g.stream().allMatch(p -> !now.isBefore(p.until()));
            (ended ? expired : plans).add(card(g, now, ended, courses, attempts.values(), pending));
        }
        plans.sort(Comparator.comparing(PlanCard::validUntil));
        expired.sort(Comparator.comparing(PlanCard::validUntil).reversed());

        List<PendingAssignment> pend = pending.stream()
                .map(p -> new PendingAssignment(p.id(), p.className(), p.dueAt(), now.isAfter(p.dueAt()), p.courseId())).toList();
        return new Progress(plans, expired, series(attempts.values()), weeks(user, org, now), streak(user, org, now), pend);
    }

    private PlanCard card(List<Plan> g, Instant now, boolean ended, Map<UUID, Course> courses, java.util.Collection<Attempt> attempts, List<Pending> pending) {
        Plan first = g.get(0);
        Instant from = g.stream().map(Plan::from).min(Comparator.naturalOrder()).orElseThrow();
        Instant until = g.stream().map(Plan::until).max(Comparator.naturalOrder()).orElseThrow();
        boolean active = g.stream().anyMatch(p -> !now.isBefore(p.from()) && now.isBefore(p.until()));
        long daysLeft = ended ? 0 : Math.max(0, (long) Math.ceil(Duration.between(now, until).toSeconds() / 86400.0));
        Map<UUID, Plan> distinct = new LinkedHashMap<>();
        g.forEach(p -> distinct.putIfAbsent(p.courseId(), p));
        List<CourseProgress> list = new ArrayList<>();
        for (Plan p : distinct.values()) {
            list.add(course(p, courses.get(p.courseId()), attempts, pending, ended));
        }
        String name = "ORDER".equals(first.source()) ? first.planName() : null;
        return new PlanCard(first.source(), first.groupId(), name, "SEAT".equals(first.source()) ? first.org() : null, from, until, active, ended, daysLeft, distinct.size(),
                first.planMax(), list);
    }

    private CourseProgress course(Plan p, Course c, java.util.Collection<Attempt> attempts, List<Pending> pending, boolean readOnly) {
        int total = c == null ? 0 : c.lessonsTotal() + c.testsTotal();
        int done = c == null ? 0 : c.lessonsDone() + c.testsDone();
        int percent = total == 0 ? 0 : (int) Math.round(100.0 * done / total);
        List<Attempt> mine = attempts.stream().filter(a -> p.courseId().equals(a.courseId())).sorted(Comparator.comparing(Attempt::at)).toList();
        List<Attempt> scored = mine.stream().filter(a -> a.score() != null).toList();
        Score latest = scored.isEmpty() ? null : scored.get(scored.size() - 1).score();
        Score best = scored.stream().map(Attempt::score).max(Comparator.comparingDouble(Score::value)).orElse(null);
        int open = (int) pending.stream().filter(x -> p.courseId().equals(x.courseId())).count();
        return new CourseProgress(p.courseId(), p.code(), p.title(), percent, c == null ? 0 : c.lessonsDone(), c == null ? 0 : c.lessonsTotal(), c == null ? 0 : c.chaptersDone(),
                c == null ? 0 : c.chaptersTotal(), c == null ? 0 : c.testsDone(), c == null ? 0 : c.testsTotal(), mine.size(), latest, best, open,
                readOnly || c == null ? null : c.resume(), c == null ? null : c.lastActivity());
    }

    record Attempt(UUID id, UUID courseId, String exam, Instant at, Score score) {}

    /** One entry per submitted attempt, with the aggregate score that is displayed (null when it has none). */
    static Map<UUID, Attempt> attempts(List<Row> rows) {
        Map<UUID, List<Row>> by = new LinkedHashMap<>();
        rows.forEach(r -> by.computeIfAbsent(r.attemptId(), k -> new ArrayList<>()).add(r));
        Map<UUID, Attempt> out = new LinkedHashMap<>();
        by.forEach((id, rs) -> {
            Row h = rs.get(0);
            Map<String, DisplayedScores.Candidate> shown = DisplayedScores.select(rs.stream().map(Row::score).filter(java.util.Objects::nonNull).toList());
            DisplayedScores.Candidate agg = shown.containsKey("OVERALL") ? shown.get("OVERALL") : shown.get("TOTAL");
            out.put(id, new Attempt(id, h.courseId(), h.exam(), h.submittedAt(), agg == null ? null : new Score(agg.scaled(), agg.kind(), agg.source(), h.exam())));
        });
        return out;
    }

    static List<ScorePoint> series(java.util.Collection<Attempt> attempts) {
        Map<String, List<Attempt>> byExam = new LinkedHashMap<>();
        attempts.stream().filter(a -> a.score() != null).sorted(Comparator.comparing(Attempt::at)).forEach(a -> byExam.computeIfAbsent(a.exam(), k -> new ArrayList<>()).add(a));
        List<ScorePoint> out = new ArrayList<>();
        byExam.forEach((exam, list) -> {
            for (int i = 0; i < list.size(); i++) {
                double sum = 0;
                int n = 0;
                for (int j = Math.max(0, i - PREDICT_WINDOW + 1); j <= i; j++) {
                    sum += list.get(j).score().value();
                    n++;
                }
                Attempt a = list.get(i);
                out.add(new ScorePoint(a.id(), exam, a.at(), a.score().value(), a.score().kind(), Math.round(sum / n * 100.0) / 100.0));
            }
        });
        out.sort(Comparator.comparing(ScorePoint::at));
        return out;
    }

    private List<WeekMinutes> weeks(UUID user, UUID org, Instant now) {
        LocalDate thisWeek = now.atZone(ZONE).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate first = thisWeek.minusWeeks(WEEKS - 1);
        Map<LocalDate, Integer> seconds = new HashMap<>();
        for (Object[] r : repo.study(user, org, first)) {
            seconds.merge(((LocalDate) r[0]).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), (Integer) r[1], Integer::sum);
        }
        List<WeekMinutes> out = new ArrayList<>();
        for (int i = 0; i < WEEKS; i++) {
            LocalDate w = first.plus(i, ChronoUnit.WEEKS);
            out.add(new WeekMinutes(w, seconds.getOrDefault(w, 0) / 60));
        }
        return out;
    }

    private Streak streak(UUID user, UUID org, Instant now) {
        return repo.streak(user, org).map(s -> {
            LocalDate today = now.atZone(ZoneId.of(s.zone())).toLocalDate();
            boolean alive = s.lastDay() != null && !s.lastDay().isBefore(today.minusDays(1));
            return new Streak(alive ? s.current() : 0, s.longest(), s.lastDay());
        }).orElse(new Streak(0, 0, null));
    }
}
