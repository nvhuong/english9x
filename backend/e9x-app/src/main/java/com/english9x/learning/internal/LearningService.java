package com.english9x.learning.internal;

import com.english9x.billing.api.Entitlements;
import com.english9x.content.question.api.QuestionExamView;
import com.english9x.content.question.api.QuestionVersions;
import com.english9x.content.strategy.api.StrategyLessonExamView;
import com.english9x.content.strategy.api.StrategyLessonVersions;
import com.english9x.content.test.api.TestExamView;
import com.english9x.content.test.api.TestVersions;
import com.english9x.course.api.CourseOutline;
import com.english9x.course.api.CourseVersions;
import com.english9x.course.api.LessonAccess;
import com.english9x.course.api.LessonGate;
import com.english9x.course.api.LessonNotFoundException;
import com.english9x.domain.learning.ChapterRef;
import com.english9x.domain.learning.ChapterTestAttempts;
import com.english9x.domain.learning.LessonStatus;
import com.english9x.domain.learning.Streaks;
import com.english9x.domain.learning.StudyTime;
import com.english9x.domain.learning.UnlockPolicy;
import com.english9x.domain.learning.UnlockRule;
import com.english9x.domain.scoring.Answer;
import com.english9x.domain.scoring.AnswerKey;
import com.english9x.domain.scoring.InteractionKind;
import com.english9x.domain.scoring.ItemResult;
import com.english9x.domain.scoring.ObjectiveScorers;
import com.english9x.learning.internal.LearningDtos.*;
import com.english9x.learning.internal.LearningRepository.Key;
import com.english9x.learning.internal.LearningRepository.Lesson;
import com.english9x.org.api.CurrentContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The learner's side of a course (spec 8): open a lesson (the lesson gate decides), practise with the answer shown at once, finish a lesson, sit a chapter test, and
 * the study clock. Every method acts as the person bound to the thread and for nobody else. The answer a learner sees is only that of a practice question of a lesson
 * they are entitled to, just answered ({@code learning_practice_key} re-checks that in SQL); chapter-test answers are scored here and never shown.
 */
@Service
class LearningService {

    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int STREAK_LOOKBACK_DAYS = 800;

    private final LearningRepository repo;
    private final LessonGate gate;
    private final CourseOutline outline;
    private final CourseVersions courses;
    private final Entitlements entitlements;
    private final StrategyLessonVersions lessons;
    private final QuestionVersions questions;
    private final TestVersions tests;
    private final Clock clock;
    private final LearningProperties props;
    private final ObjectMapper json;

    LearningService(LearningRepository repo, LessonGate gate, CourseOutline outline, CourseVersions courses, Entitlements entitlements,
                    StrategyLessonVersions lessons, QuestionVersions questions, TestVersions tests, Clock clock, LearningProperties props, ObjectMapper json) {
        this.repo = repo;
        this.gate = gate;
        this.outline = outline;
        this.courses = courses;
        this.entitlements = entitlements;
        this.lessons = lessons;
        this.questions = questions;
        this.tests = tests;
        this.clock = clock;
        this.props = props;
        this.json = json;
    }

    static UUID me() {
        return CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no user")).userId();
    }

    /** The lesson, once the gate lets this person in. */
    private Lesson open(UUID lessonId, UUID user, Instant now) {
        Lesson l = repo.lesson(lessonId).orElseThrow(LearningProblems::notFound);
        LessonAccess access;
        try {
            access = gate.check(user, lessonId, now);
        } catch (LessonNotFoundException e) {
            throw LearningProblems.notFound();
        }
        if (access == LessonAccess.NOT_ENTITLED) {
            throw LearningProblems.notEntitled();
        }
        if (access == LessonAccess.LOCKED) {
            throw LearningProblems.locked();
        }
        return l;
    }

    // ---- lessons ----

    @Transactional(readOnly = true)
    public LessonView lesson(UUID lessonId) {
        UUID user = me();
        Instant now = clock.instant();
        Lesson l = open(lessonId, user, now);
        if (!"LESSON".equals(l.kind())) {
            throw LearningProblems.notFound();
        }
        StrategyLessonExamView view = lessons.examView(l.refId()).orElseThrow(LearningProblems::notFound);
        List<Question> qs = new ArrayList<>();
        for (JsonNode b : view.blocks()) {
            if ("embedded-question".equals(b.path("type").asText())) {
                try {
                    questions.examView(UUID.fromString(b.path("questionVersionId").asText())).ifPresent(q -> qs.add(question(q)));
                } catch (IllegalArgumentException ignored) {
                    // a malformed id names no question
                }
            }
        }
        return new LessonView(lessonId, l.courseId(), view.title(), repo.progress(user, l.courseId(), l.refId()).completed(), view.blocks(), view.questionTypeCodes(), qs);
    }

    private static Question question(QuestionExamView q) {
        return new Question(q.versionId(), q.typeCode(), q.content(), q.points());
    }

    @Transactional
    public PracticeResult practice(UUID lessonId, PracticeRequest req) {
        if (req == null || req.questionVersionId() == null) {
            throw LearningProblems.badRequest("learning-question-missing", "questionVersionId is required.");
        }
        UUID user = me();
        Instant now = clock.instant();
        Lesson l = open(lessonId, user, now);
        if (!"LESSON".equals(l.kind())) {
            throw LearningProblems.notFound();
        }
        Key key = repo.practiceKey(lessonId, req.questionVersionId(), now).orElseThrow(LearningProblems::notFound);
        ItemResult r = score(key, req.answer());
        repo.insertPractice(user, key.courseId(), key.refId(), key, req.answer(), r.correct(), r.earned(), now);
        return new PracticeResult(r.correct(), r.earned(), key.explanation(), AnswerMapper.display(key.answerKey()));
    }

    private ItemResult score(Key key, JsonNode answer) {
        InteractionKind kind = InteractionKind.valueOf(key.kind());
        if (!kind.autoScored()) {
            throw LearningProblems.rejected("learning-not-auto-scored", "This kind of question is not scored by the machine.");
        }
        AnswerKey k = AnswerMapper.key(key.answerKey(), key.points(), key.questionVersionId());
        return ObjectiveScorers.forKind(kind).score(k, AnswerMapper.answer(answer));
    }

    @Transactional
    public Completion complete(UUID lessonId, String zoneHint) {
        UUID user = me();
        Instant now = clock.instant();
        Lesson l = open(lessonId, user, now);
        if (!"LESSON".equals(l.kind())) {
            throw LearningProblems.rejected("learning-not-a-lesson", "A chapter test is finished by sitting it.");
        }
        repo.lockProgress(user, l.courseId(), l.refId(), l.kind());
        ZoneId zone = zone(user, zoneHint, now);
        LocalDate today = now.atZone(zone).toLocalDate();
        if (repo.complete(user, l.courseId(), l.refId(), now)) {
            repo.addLessonCompleted(user, today, now);
        }
        return new Completion(true, refreshStreak(user, today, now));
    }

    // ---- chapter tests ----

    @Transactional(readOnly = true)
    public ChapterTestView chapterTest(UUID lessonId) {
        UUID user = me();
        Instant now = clock.instant();
        Lesson l = open(lessonId, user, now);
        if (!"CHAPTER_TEST".equals(l.kind())) {
            throw LearningProblems.notFound();
        }
        TestExamView test = tests.forExamRoom(l.refId()).orElseThrow(LearningProblems::notFound);
        List<Group> groups = new ArrayList<>();
        for (TestExamView.Part p : test.parts()) {
            for (TestExamView.Group g : p.groups()) {
                groups.add(new Group(g.content(), g.questions().stream().map(LearningService::question).toList()));
            }
        }
        int used = repo.attemptsUsed(user, l.courseId(), l.refId());
        int max = l.maxAttempts() == null ? 0 : l.maxAttempts();
        return new ChapterTestView(lessonId, l.courseId(), l.title(), l.passThreshold(), max, used, ChapterTestAttempts.canRetake(used, max),
                repo.progress(user, l.courseId(), l.refId()).bestScore(), groups);
    }

    @Transactional
    public AttemptResult attempt(UUID lessonId, AttemptRequest req, String zoneHint) {
        UUID user = me();
        Instant now = clock.instant();
        Lesson l = open(lessonId, user, now);
        if (!"CHAPTER_TEST".equals(l.kind())) {
            throw LearningProblems.notFound();
        }
        repo.lockProgress(user, l.courseId(), l.refId(), l.kind());
        int used = repo.attemptsUsed(user, l.courseId(), l.refId());
        int max = l.maxAttempts() == null ? 0 : l.maxAttempts();
        if (!ChapterTestAttempts.canRetake(used, max)) {
            throw LearningProblems.rejected("learning-attempts-exhausted", "You have used all " + max + " attempts of this test.");
        }
        List<Key> keys = repo.testKeys(lessonId, now);
        if (keys.isEmpty()) {
            throw LearningProblems.notFound();
        }
        Map<UUID, JsonNode> given = new HashMap<>();
        if (req != null && req.answers() != null) {
            for (Answered a : req.answers()) {
                if (a != null && a.questionVersionId() != null) {
                    given.put(a.questionVersionId(), a.answer());
                }
            }
        }
        double raw = 0;
        double rawMax = 0;
        Map<String, JsonNode> stored = new LinkedHashMap<>();
        for (Key k : keys) {
            if (!InteractionKind.valueOf(k.kind()).autoScored()) {
                continue; // a chapter test is a quick machine-scored check: a writing or speaking item is left out of the score
            }
            ItemResult r = score(k, given.get(k.questionVersionId()));
            raw += r.earned();
            rawMax += r.max();
            stored.put(k.questionVersionId().toString(), given.get(k.questionVersionId()));
        }
        double score = rawMax == 0 ? 0 : Math.round(raw / rawMax * 10000.0) / 10000.0;
        boolean passed = score >= l.passThreshold();
        int no = used + 1;
        repo.insertAttempt(user, l.courseId(), l.refId(), no, raw, rawMax, score, passed, json.valueToTree(stored), now);
        Double before = repo.progress(user, l.courseId(), l.refId()).bestScore();
        List<Double> all = before == null ? List.of(score) : List.of(before, score);
        double best = ChapterTestAttempts.best(all).orElse(score);
        repo.setBest(user, l.courseId(), l.refId(), best, now);
        refreshStreak(user, now.atZone(zone(user, zoneHint, now)).toLocalDate(), now);
        return new AttemptResult(no, score, raw, rawMax, passed, best, no, max == 0 ? null : max - no);
    }

    // ---- study time ----

    @Transactional
    public HeartbeatResult heartbeat(HeartbeatRequest req) {
        if (req == null || req.courseId() == null || req.lessonId() == null) {
            throw LearningProblems.badRequest("learning-heartbeat-incomplete", "courseId and lessonId are required.");
        }
        UUID user = me();
        Instant now = clock.instant();
        Lesson l = open(req.lessonId(), user, now);
        if (!l.courseId().equals(req.courseId())) {
            throw LearningProblems.notFound();
        }
        ZoneId zone = zone(user, req.timeZone(), now);
        LocalDate today = now.atZone(zone).toLocalDate();
        Instant last = repo.lastHeartbeat(user);
        // signals closer than half the interval add nothing the clock does not already count: they are not stored (the table cannot grow by hammering)
        if (last == null || Duration.between(last, now).abs().compareTo(props.heartbeatInterval().dividedBy(2)) >= 0) {
            repo.heartbeat(user, now, today, l.courseId(), l.id());
        }
        int seconds = (int) StudyTime.credited(repo.heartbeats(user, today), props.idleTimeout()).toSeconds();
        repo.setSeconds(user, today, seconds, now);
        return new HeartbeatResult(seconds, refreshStreak(user, today, now));
    }

    /** The zone the person's days are cut in: the one the browser last reported, else the one stored, else Asia/Ho_Chi_Minh. */
    private ZoneId zone(UUID user, String hint, Instant now) {
        String stored = repo.timeZone(user);
        if (hint != null && !hint.isBlank() && !hint.equals(stored)) {
            try {
                ZoneId z = ZoneId.of(hint);
                repo.setTimeZone(user, z.getId(), now);
                return z;
            } catch (DateTimeException ignored) {
                // an unknown zone is not taken
            }
        }
        if (stored != null) {
            try {
                return ZoneId.of(stored);
            } catch (DateTimeException ignored) {
                // fall through
            }
        }
        return DEFAULT_ZONE;
    }

    private int refreshStreak(UUID user, LocalDate today, Instant now) {
        int current = Streaks.current(repo.days(user, today.minusDays(STREAK_LOOKBACK_DAYS)), today, props.streak().minMinutes());
        repo.setStreak(user, current, current, today, now);
        return current;
    }

    // ---- the chapter map ----

    @Transactional(readOnly = true)
    public ChapterMapView map(UUID courseId) {
        UUID user = me();
        Instant now = clock.instant();
        UUID version = courses.publishedVersionOf(courseId).orElseThrow(LearningProblems::notFound);
        if (!entitlements.activeFor(user, courseId, now)) {
            throw LearningProblems.notEntitled();
        }
        LearningRepository.VersionHead head = repo.versionHead(version);
        Map<UUID, LearningRepository.LessonRow> rows = new HashMap<>();
        repo.lessonsOf(version).forEach(r -> rows.put(r.id(), r));
        List<ChapterRef> chapters = outline.lessonsInOrder(version);
        Map<String, LessonStatus> done = new HashMap<>();
        rows.values().forEach(r -> {
            var p = repo.progress(user, courseId, r.refId());
            done.put(r.id().toString(), new LessonStatus(p.completed(), p.bestScore()));
        });
        UnlockRule rule = UnlockRule.valueOf(head.rule());
        List<MapChapter> out = new ArrayList<>();
        List<String> titles = repo.chapterTitles(version);
        for (int i = 0; i < chapters.size(); i++) {
            ChapterRef c = chapters.get(i);
            List<MapLesson> ls = new ArrayList<>();
            c.lessons().forEach(lr -> {
                LearningRepository.LessonRow r = rows.get(UUID.fromString(lr.id()));
                LessonStatus s = done.get(lr.id());
                ls.add(new MapLesson(r.id(), r.kind(), r.title(), s.completed(), s.bestScore(), !UnlockPolicy.isUnlocked(rule, chapters, done, lr.id())));
            });
            out.add(new MapChapter(titles.get(i), c.passThreshold(), ls));
        }
        ZoneId zone = zone(user, null, now);
        LocalDate today = now.atZone(zone).toLocalDate();
        int streak = Streaks.current(repo.days(user, today.minusDays(STREAK_LOOKBACK_DAYS)), today, props.streak().minMinutes());
        return new ChapterMapView(courseId, head.title(), head.rule(), streak, repo.seconds(user, today) / 60, out);
    }
}
