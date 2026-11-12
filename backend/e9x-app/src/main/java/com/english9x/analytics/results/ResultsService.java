package com.english9x.analytics.results;

import com.english9x.analytics.results.ResultsDtos.AiFeedback;
import com.english9x.analytics.results.ResultsDtos.AttemptResult;
import com.english9x.analytics.results.ResultsDtos.LessonRef;
import com.english9x.analytics.results.ResultsDtos.ProductiveFeedback;
import com.english9x.analytics.results.ResultsDtos.QuestionResult;
import com.english9x.analytics.results.ResultsDtos.SkillScore;
import com.english9x.analytics.results.ResultsDtos.TeacherFeedback;
import com.english9x.analytics.results.ResultsDtos.TypeRate;
import com.english9x.analytics.results.ResultsDtos.Weakness;
import com.english9x.analytics.results.ResultsRepository.Head;
import com.english9x.billing.api.Entitlements;
import com.english9x.grading.api.AttemptSolutions;
import com.english9x.grading.api.DisplayedScores;
import com.english9x.org.api.CurrentContextHolder;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The results screen of the learner (F38). It READS the outcome of grading and never grades: the score shown of a skill is the one {@link DisplayedScores} picks (the rule of the
 * teacher's queue), the right and wrong of a question come from the scorer of the automatic scoring, the feedback is what the AI and the teachers stored.
 *
 * <p><b>The key is shown after, never before.</b> Only the attempt's owner reads it (anybody else, or a made-up id, finds "not found": the row is not visible to them). While the
 * attempt is IN_PROGRESS the answer is 409 and nothing of the test; once SUBMITTED but not yet scored (the late-sync grace is running: an answer can still arrive, so a key shown
 * now could be used to tune it) the answer is the head with {@code state = SCORING} and nothing else. The solution side is read from the grader's side of the database only for an
 * attempt that was scored ({@link AttemptSolutions}).
 */
@Service
class ResultsService {

    private final ResultsRepository repo;
    private final AttemptSolutions solutions;
    private final Entitlements entitlements;
    private final Clock clock;
    private final int recommendationCount;

    ResultsService(ResultsRepository repo, AttemptSolutions solutions, Entitlements entitlements, Clock clock,
            @Value("${e9x.results.recommendation-count:3}") int recommendationCount) {
        this.repo = repo;
        this.solutions = solutions;
        this.entitlements = entitlements;
        this.clock = clock;
        this.recommendationCount = recommendationCount;
    }

    private static UUID me() {
        return CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no user")).userId();
    }

    @Transactional(readOnly = true)
    public AttemptResult attempt(UUID attemptId) {
        UUID user = me();
        Head head = repo.head(attemptId, user).orElseThrow(ResultsProblems::notFound);
        if (!"SUBMITTED".equals(head.status())) {
            throw ResultsProblems.notSubmitted();
        }
        if (!repo.graded(attemptId)) {
            return new AttemptResult(head.id(), "SCORING", head.examCode(), head.interrupted(), head.submittedAt(), List.of(), List.of(), List.of());
        }
        Optional<List<AttemptSolutions.Item>> items = solutions.of(attemptId, user, head.orgId());
        if (items.isEmpty()) {
            return new AttemptResult(head.id(), "SCORING", head.examCode(), head.interrupted(), head.submittedAt(), List.of(), List.of(), List.of());
        }
        Map<String, String> names = repo.typeNames();
        List<QuestionResult> questions = new ArrayList<>();
        for (AttemptSolutions.Item i : items.get()) {
            questions.add(new QuestionResult(i.questionId(), i.sectionCode(), i.skill(), i.typeCode(), names.getOrDefault(i.typeCode(), i.typeCode()), i.scored(), i.correct(),
                    i.earned(), i.max(), i.answer(), i.correctAnswer(), i.explanation(), i.transcript()));
        }
        List<SkillScore> skills = skills(attemptId);
        return new AttemptResult(head.id(), "READY", head.examCode(), head.interrupted(), head.submittedAt(), skills, questions, feedback(attemptId));
    }

    /** One entry per skill of the attempt, then the aggregate (TOTAL / OVERALL) when there is one; a skill with no score yet is PENDING. */
    private List<SkillScore> skills(UUID attemptId) {
        List<ResultsRepository.Stored> stored = repo.scores(attemptId);
        Map<String, DisplayedScores.Candidate> chosen = DisplayedScores.select(stored.stream().filter(s -> s.scaled() != null).map(ResultsRepository.Stored::candidate).toList());
        Map<String, ResultsRepository.Stored> byKey = new LinkedHashMap<>();
        stored.forEach(s -> byKey.put(s.skill() + "/" + s.kind() + "/" + s.source(), s));
        List<String> order = new ArrayList<>(repo.skills(attemptId));
        stored.stream().map(ResultsRepository.Stored::skill).distinct().filter(s -> !order.contains(s)).forEach(order::add);
        List<SkillScore> out = new ArrayList<>();
        List<SkillScore> aggregates = new ArrayList<>();
        for (String skill : order) {
            boolean aggregate = isAggregate(skill);
            DisplayedScores.Candidate c = chosen.get(skill);
            SkillScore row;
            if (c != null) {
                ResultsRepository.Stored s = byKey.get(skill + "/" + c.kind() + "/" + c.source());
                row = new SkillScore(skill, aggregate, c.scaled(), s.raw(), s.rawMax(), c.kind(), c.source());
            } else {
                // points without a converted score (no published table): shown as the raw points, labelled by the row they come from; or nothing yet
                Optional<ResultsRepository.Stored> raw = stored.stream().filter(s -> s.skill().equals(skill)).findFirst();
                row = raw.map(s -> new SkillScore(skill, aggregate, null, s.raw(), s.rawMax(), s.kind(), s.source()))
                        .orElseGet(() -> new SkillScore(skill, aggregate, null, null, null, "PENDING", null));
            }
            (aggregate ? aggregates : out).add(row);
        }
        out.addAll(aggregates);
        return out;
    }

    private static boolean isAggregate(String skill) {
        return "TOTAL".equals(skill) || "OVERALL".equals(skill);
    }

    private List<ProductiveFeedback> feedback(UUID attemptId) {
        Map<String, List<AiFeedback>> ai = new LinkedHashMap<>();
        repo.evaluations(attemptId).forEach(e -> ai.computeIfAbsent(e.skill(), k -> new ArrayList<>()).add(new AiFeedback(e.questionId(), e.band(), e.result())));
        Map<String, TeacherFeedback> teacher = new LinkedHashMap<>();
        repo.reviews(attemptId).forEach(r -> teacher.put(r.skill(), new TeacherFeedback(r.criteria(), r.comment(), r.band())));
        List<String> skills = new ArrayList<>(ai.keySet());
        teacher.keySet().stream().filter(s -> !skills.contains(s)).forEach(skills::add);
        return skills.stream().map(s -> new ProductiveFeedback(s, ai.getOrDefault(s, List.of()), teacher.get(s))).toList();
    }

    @Transactional(readOnly = true)
    public Weakness weakness(String exam) {
        UUID user = me();
        String examCode = exam == null || exam.isBlank() ? null : exam.trim();
        List<TypeRate> rates = repo.typeRates(user, examCode);
        Instant now = clock.instant();
        Map<String, List<LessonRef>> open = new LinkedHashMap<>();
        Set<UUID> allowed = new java.util.HashSet<>();
        Set<UUID> refused = new java.util.HashSet<>();
        List<String> candidates = rates.stream().filter(t -> t.rate() < 1.0).map(TypeRate::typeCode).toList();
        for (ResultsRepository.Lesson l : repo.methodLessons(examCode, candidates, now)) {
            // the database answer already holds only courses the person is entitled to; the gate of the platform confirms each course (one rule for learning access)
            boolean ok = allowed.contains(l.courseId()) || (!refused.contains(l.courseId()) && entitlements.activeFor(user, l.courseId(), now));
            (ok ? allowed : refused).add(l.courseId());
            if (ok) {
                open.computeIfAbsent(l.typeCode(), k -> new ArrayList<>()).add(new LessonRef(l.courseId(), l.courseTitle(), l.lessonId(), l.title()));
            }
        }
        return new Weakness(examCode, repo.attemptsWithStats(user, examCode), rates, Recommendations.pick(rates, open, recommendationCount));
    }
}
