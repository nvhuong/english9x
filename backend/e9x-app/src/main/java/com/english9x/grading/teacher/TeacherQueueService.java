package com.english9x.grading.teacher;

import com.english9x.audit.api.AuditLog;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.domain.grading.DisplayedScore;
import com.english9x.domain.grading.GradingMode;
import com.english9x.domain.grading.GradingQueueItem;
import com.english9x.domain.grading.OfficialScore;
import com.english9x.domain.grading.ScoreRecord;
import com.english9x.content.media.api.StagedFiles;
import com.english9x.grading.ai.AiStore;
import com.english9x.grading.ai.AiStore.Attempt;
import com.english9x.grading.ai.AiStore.Item;
import com.english9x.grading.ai.Bands;
import com.english9x.grading.auto.ScoreAnnouncer;
import com.english9x.grading.db.GraderDatabase;
import com.english9x.grading.teacher.GradingStore.Evaluation;
import com.english9x.grading.teacher.TeacherDtos.*;
import com.english9x.identity.api.UserDirectory;
import com.english9x.identity.api.UserView;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The teachers' queue (spec 10.3, F35): list, claim, grade, release; and the administrator's list and reassignment.
 *
 * <p><b>Two connections, two people.</b> The queue itself is read and changed on the request connection as the acting person, under Row-Level Security (a teacher sees the OPEN
 * items of their own side and the items they hold; nobody else's). The learner's answers, recordings and the AI's judgement are read, and the review and the score are written,
 * on the grader's connection in the LEARNER's context (user and organisation of the attempt, from the queue item the teacher is allowed to see: never from the request), and
 * only for an item the acting teacher holds.
 *
 * <p><b>Claim.</b> Optimistic: {@code UPDATE ... WHERE version = ? AND state = 'OPEN'}; of two teachers who claim at once one gets 409. The claim is due after
 * {@code e9x.grading.claim-timeout}; {@code release_expired_claim} returns an overdue one to the queue.
 *
 * <p><b>Review.</b> One review per skill (Writing, Speaking): a score per criterion of the grading skill, a comment; the band is their mean to the nearest half, like the AI's.
 * The teacher's score is OFFICIAL and replaces the AI's whatever the mode ({@code domain.grading.OfficialScore}); the deviation (teacher minus AI) is stored for monitoring. When
 * every skill is graded the item is DONE and {@code AttemptScored(official=true)} is announced.
 */
@Service
class TeacherQueueService {

    private static final double MAX_SCORE = 9;

    private final QueueRepository repo;
    private final GradingStore store;
    private final AiStore ai;
    private final GraderDatabase grader;
    private final QuestionTypes types;
    private final StagedFiles staged;
    private final ScoreAnnouncer announcer;
    private final Authz authz;
    private final AuditLog audit;
    private final UserDirectory users;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final Clock clock;
    private final ObjectMapper json;
    private final Duration timeout;

    TeacherQueueService(QueueRepository repo, GradingStore store, AiStore ai, GraderDatabase grader, QuestionTypes types, StagedFiles staged, ScoreAnnouncer announcer, Authz authz,
            AuditLog audit, UserDirectory users, PlatformTransactionManager transactions, Clock clock, ObjectMapper json, @Value("${e9x.grading.claim-timeout:24h}") Duration timeout) {
        this.repo = repo;
        this.store = store;
        this.ai = ai;
        this.grader = grader;
        this.types = types;
        this.staged = staged;
        this.announcer = announcer;
        this.authz = authz;
        this.audit = audit;
        this.users = users;
        this.readTx = new TransactionTemplate(transactions);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactions);
        this.clock = clock;
        this.json = json;
        this.timeout = timeout;
    }

    // ---- the teacher ------------------------------------------------------------------------------------------------------------------

    List<QueueItemView> list(String exam) {
        authz.require(Role.TEACHER, Optional.empty());
        UUID me = me();
        return readTx.execute(s -> repo.forTeacher(me, exam == null || exam.isBlank() ? null : exam.strip())).stream().map(i -> view(i, me)).toList();
    }

    QueueItemView claim(UUID id) {
        authz.require(Role.TEACHER, Optional.empty());
        UUID me = me();
        Instant now = clock.instant();
        QueueRepository.Item item = visible(id);
        if (item.userId().equals(me)) {
            throw TeacherProblems.conflict("own-attempt", "Own attempt", "A teacher does not grade their own attempt.");
        }
        if (!"OPEN".equals(item.state())) {
            throw taken();
        }
        new GradingQueueItem(id.toString()).claim(me.toString(), now); // the rule of the domain (an OPEN item only)
        int n = writeTx.execute(s -> repo.claim(id, item.version(), me, now, now.plus(timeout)));
        if (n == 0) {
            throw taken(); // somebody was faster: the optimistic version no longer matches
        }
        return view(visible(id), me);
    }

    /** The holder puts the item back in the queue. */
    QueueItemView release(UUID id) {
        authz.require(Role.TEACHER, Optional.empty());
        UUID me = me();
        QueueRepository.Item item = visible(id);
        if (!"CLAIMED".equals(item.state()) || !me.equals(item.claimedBy())) {
            throw TeacherProblems.conflict("not-claimed-by-you", "Not claimed by you", "Only the teacher who holds the submission can return it.");
        }
        if (writeTx.execute(s -> repo.release(id, item.version(), me)) == 0) {
            throw TeacherProblems.conflict("claim-changed", "Claim changed", "The submission changed hands meanwhile.");
        }
        return view(visible(id), me);
    }

    /** What the teacher grades: the work, the AI's judgement, the criteria. Only for an item they hold. */
    Detail detail(UUID id) {
        authz.require(Role.TEACHER, Optional.empty());
        UUID me = me();
        QueueRepository.Item item = held(id, me);
        return grader.read(item.userId(), item.ownerOrgId(), j -> {
            Attempt a = ai.attempt(j, item.attemptId()).orElseThrow(TeacherProblems::notFound);
            Map<String, List<Item>> bySkill = skillsToGrade(j, a);
            Map<UUID, Evaluation> evaluations = store.evaluations(j, a.id());
            Map<String, GradingStore.Review> reviews = new LinkedHashMap<>();
            store.reviews(j, a.id()).forEach(r -> reviews.put(r.skill(), r));
            List<SkillView> skills = new ArrayList<>();
            for (Map.Entry<String, List<Item>> e : bySkill.entrySet()) {
                List<TaskView> tasks = new ArrayList<>();
                for (Item i : e.getValue()) {
                    Evaluation ev = evaluations.get(i.questionId());
                    tasks.add(new TaskView(i.questionId(), i.typeCode(), taskText(i.content()), answerText(i.answer()), i.recordingPath() != null,
                            ev == null ? null : new AiView(ev.band(), ev.result())));
                }
                GradingStore.Review r = reviews.get(e.getKey());
                skills.add(new SkillView(e.getKey(), tasks, criteria(j, e.getValue()), store.aiBand(j, a.id(), e.getKey()).map(BigDecimal::doubleValue).orElse(null),
                        r == null ? null : new ReviewView(numbers(r.criteria()), r.comment(), r.band())));
            }
            return new Detail(item.id(), item.examCode(), item.state(), item.claimDueAt(), skills);
        });
    }

    record Recording(byte[] bytes, String mime) {}

    Recording recording(UUID id, UUID questionId) {
        authz.require(Role.TEACHER, Optional.empty());
        QueueRepository.Item item = held(id, me());
        Item found = grader.read(item.userId(), item.ownerOrgId(), j -> {
            Attempt a = ai.attempt(j, item.attemptId()).orElseThrow(TeacherProblems::notFound);
            return ai.items(j, a.id(), a.testVersionId()).stream().filter(i -> questionId.equals(i.questionId()) && i.recordingPath() != null).findFirst().orElseThrow(TeacherProblems::notFound);
        });
        Path tmp = null;
        try {
            tmp = Files.createTempFile("e9x-review-", ".webm");
            staged.get(found.recordingPath(), tmp);
            return new Recording(Files.readAllBytes(tmp), found.recordingMime());
        } catch (IOException e) {
            throw new IllegalStateException("the recording could not be read", e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // a temp file
                }
            }
        }
    }

    ReviewResult review(UUID id, ReviewRequest body) {
        authz.require(Role.TEACHER, Optional.empty());
        UUID me = me();
        QueueRepository.Item item = held(id, me);
        if (body == null || body.criteria() == null || body.criteria().isEmpty()) {
            throw TeacherProblems.validation("criteria", "Required", "Give a score for each criterion.");
        }
        String comment = body.comment() == null ? "" : body.comment().strip();
        if (comment.length() > 4000) {
            throw TeacherProblems.validation("comment", "TooLong", "The comment is at most 4000 characters.");
        }
        Instant now = clock.instant();
        ReviewResult result = grader.write(item.userId(), item.ownerOrgId(), j -> {
            Integer version = store.claimedVersion(j, id, me).orElseThrow(() -> TeacherProblems.conflict("claim-lost", "Claim lost", "You no longer hold this submission."));
            Attempt a = ai.attempt(j, item.attemptId()).orElseThrow(TeacherProblems::notFound);
            Map<String, List<Item>> bySkill = skillsToGrade(j, a);
            String skill = body.skill() == null || body.skill().isBlank() ? (bySkill.size() == 1 ? bySkill.keySet().iterator().next() : null) : body.skill().strip();
            if (skill == null || !bySkill.containsKey(skill)) {
                throw TeacherProblems.validation("skill", "Unknown", "Name the skill to grade: " + String.join(", ", bySkill.keySet()) + ".");
            }
            Set<String> reviewed = new LinkedHashSet<>();
            store.reviews(j, a.id()).forEach(r -> reviewed.add(r.skill()));
            if (reviewed.contains(skill)) {
                throw TeacherProblems.conflict("already-reviewed", "Already reviewed", "This skill has been graded already.");
            }
            if (store.hasOfficial(j, a.id(), skill)) {
                throw TeacherProblems.conflict("already-official", "Already graded", "This skill already has an official score.");
            }
            ObjectNode criteria = validated(body.criteria(), criteria(j, bySkill.get(skill)));
            double mean = body.criteria().values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            BigDecimal band = BigDecimal.valueOf(Bands.nearestHalf(mean)).setScale(2, RoundingMode.HALF_UP);
            Optional<BigDecimal> aiBand = store.aiBand(j, a.id(), skill);
            // the teacher's score is OFFICIAL whatever the mode and replaces the AI's (domain rule)
            DisplayedScore shown = OfficialScore.select(GradingMode.TEACHER, aiBand.map(b -> new ScoreRecord("AI", b.doubleValue(), now)),
                    Optional.of(new ScoreRecord("TEACHER", band.doubleValue(), now))).orElseThrow();
            store.insertReview(j, id, a.id(), a.userId(), a.orgId(), skill, me, criteria, comment, band, aiBand.orElse(null));
            store.insertScore(j, a.id(), a.userId(), a.orgId(), skill, shown.kind().name(), band);
            reviewed.add(skill);
            List<String> remaining = new ArrayList<>();
            for (String s : bySkill.keySet()) {
                if (!reviewed.contains(s) && !store.hasOfficial(j, a.id(), s)) {
                    remaining.add(s);
                }
            }
            boolean done = remaining.isEmpty();
            if (done && !store.markDone(j, id, version, now)) {
                throw TeacherProblems.conflict("claim-lost", "Claim lost", "You no longer hold this submission.");
            }
            return new ReviewResult(skill, band.doubleValue(), done, remaining);
        });
        announcer.announce(item.userId(), item.ownerOrgId(), item.attemptId());
        return result;
    }

    // ---- the administrator ------------------------------------------------------------------------------------------------------------

    List<AdminQueueItemView> adminList(String exam, String state) {
        requireAdmin();
        if (state != null && !Set.of("OPEN", "CLAIMED", "DONE").contains(state)) {
            throw TeacherProblems.validation("state", "InvalidState", "state must be OPEN, CLAIMED or DONE");
        }
        List<QueueRepository.Item> items = readTx.execute(s -> repo.forAdmin(exam == null || exam.isBlank() ? null : exam.strip(), state));
        Map<UUID, String> names = new LinkedHashMap<>();
        List<AdminQueueItemView> out = new ArrayList<>();
        for (QueueRepository.Item i : items) {
            String name = i.claimedBy() == null ? null : names.computeIfAbsent(i.claimedBy(), u -> users.findById(u).map(UserView::displayName).orElse(null));
            out.add(new AdminQueueItemView(i.id(), i.examCode(), i.state(), i.reason(), i.gradingOrgId() == null ? "ENGLISH9X" : "CENTER", i.createdAt(), i.claimedBy(), name, i.claimDueAt()));
        }
        return out;
    }

    /** The administrator of the side hands an item (OPEN or CLAIMED) to a teacher of the side; the deadline starts again. */
    AdminQueueItemView reassign(UUID id, UUID teacherId) {
        requireAdmin();
        if (teacherId == null) {
            throw TeacherProblems.validation("teacherId", "Required", "Name the teacher.");
        }
        Instant now = clock.instant();
        return writeTx.execute(s -> {
            QueueRepository.Item item = repo.find(id).orElseThrow(TeacherProblems::notFound);
            if ("DONE".equals(item.state())) {
                throw TeacherProblems.conflict("already-graded", "Already graded", "A graded submission cannot be handed over.");
            }
            if (item.userId().equals(teacherId)) {
                throw TeacherProblems.unprocessable("not-a-teacher", "Not a teacher of this queue", "teacherId", "OwnAttempt", "A teacher does not grade their own attempt.");
            }
            if (!repo.isTeacherOfSide(item, teacherId)) {
                throw TeacherProblems.unprocessable("not-a-teacher", "Not a teacher of this queue", "teacherId", "NotATeacher", "That person is not a teacher of this exam on this side.");
            }
            if (repo.reassign(id, item.version(), teacherId, now, now.plus(timeout)) == 0) {
                throw TeacherProblems.conflict("claim-changed", "Claim changed", "The submission changed hands meanwhile.");
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("teacherId", teacherId.toString());
            detail.put("examCode", item.examCode());
            audit.record("grading-queue.reassign", "grading-queue-item", id.toString(), detail);
            QueueRepository.Item now2 = repo.find(id).orElseThrow(TeacherProblems::notFound);
            String name = users.findById(teacherId).map(UserView::displayName).orElse(null);
            return new AdminQueueItemView(now2.id(), now2.examCode(), now2.state(), now2.reason(), now2.gradingOrgId() == null ? "ENGLISH9X" : "CENTER", now2.createdAt(), now2.claimedBy(), name,
                    now2.claimDueAt());
        });
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------------------

    private void requireAdmin() {
        if (!authz.has(Role.SYS_ADMIN, Optional.empty()) && !authz.has(Role.ORG_ADMIN, Optional.empty())) {
            throw new org.springframework.security.access.AccessDeniedException("administrators only");
        }
    }

    private static UUID me() {
        return CurrentContextHolder.current().orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("nobody is signed in")).userId();
    }

    /** The item as the database lets the caller see it; 404 for one of another side or one somebody else holds. */
    private QueueRepository.Item visible(UUID id) {
        return readTx.execute(s -> repo.find(id)).orElseThrow(TeacherProblems::notFound);
    }

    private QueueRepository.Item held(UUID id, UUID me) {
        QueueRepository.Item item = visible(id);
        if (!"CLAIMED".equals(item.state()) || !me.equals(item.claimedBy())) {
            throw TeacherProblems.conflict("not-claimed-by-you", "Not claimed by you", "Claim the submission first.");
        }
        return item;
    }

    private static TeacherDtos.QueueItemView view(QueueRepository.Item i, UUID me) {
        return new QueueItemView(i.id(), i.examCode(), i.state(), me.equals(i.claimedBy()), i.createdAt(), i.claimDueAt());
    }

    private static RuntimeException taken() {
        return TeacherProblems.conflict("already-claimed", "Already claimed", "Somebody else holds this submission.");
    }

    /** skill -> the questions a person grades in it (the AI's own filter: not machine-scored, in a section that has a skill). */
    private Map<String, List<Item>> skillsToGrade(org.springframework.jdbc.core.JdbcTemplate j, Attempt a) {
        Map<String, String> skillOfSection = ai.sectionSkills(j, a.id());
        Map<String, List<Item>> out = new LinkedHashMap<>();
        for (Item i : ai.items(j, a.id(), a.testVersionId())) {
            String skill = skillOfSection.get(i.sectionCode());
            if (i.questionId() != null && skill != null && !types.get(i.typeCode()).kind().autoScored()) {
                out.computeIfAbsent(skill, k -> new ArrayList<>()).add(i);
            }
        }
        // in the order of the sections of the attempt (Writing before Speaking), not of the section codes
        Map<String, List<Item>> ordered = new LinkedHashMap<>();
        for (String skill : skillOfSection.values()) {
            if (skill != null && out.containsKey(skill)) {
                ordered.put(skill, out.get(skill));
            }
        }
        out = ordered;
        return out;
    }

    /** The criteria of the published grading skills of the question types of the items, by code. */
    private List<CriterionView> criteria(org.springframework.jdbc.core.JdbcTemplate j, List<Item> items) {
        Map<String, CriterionView> out = new LinkedHashMap<>();
        for (String type : items.stream().map(Item::typeCode).distinct().toList()) {
            ai.skill(j, type).map(AiStore.Skill::criteria).filter(JsonNode::isArray).ifPresent(arr -> arr.forEach(c -> {
                String code = c.path("code").asText("");
                if (!code.isBlank()) {
                    out.putIfAbsent(code, new CriterionView(code, c.path("name").asText(code), c.has("min") ? c.path("min").asDouble() : null, c.has("max") ? c.path("max").asDouble() : null,
                            c.has("step") ? c.path("step").asDouble() : null));
                }
            }));
        }
        return new ArrayList<>(out.values());
    }

    /** The teacher's scores as stored: only criteria of the grading skill (any code if the skill has none), each within its range. */
    private ObjectNode validated(Map<String, Double> given, List<CriterionView> allowed) {
        ObjectNode out = json.createObjectNode();
        Map<String, CriterionView> byCode = new LinkedHashMap<>();
        allowed.forEach(c -> byCode.put(c.code(), c));
        for (Map.Entry<String, Double> e : given.entrySet()) {
            CriterionView c = byCode.get(e.getKey());
            if (!byCode.isEmpty() && c == null) {
                throw TeacherProblems.validation("criteria." + e.getKey(), "UnknownCriterion", "Not a criterion of the grading skill: " + e.getKey());
            }
            if (byCode.isEmpty() && !e.getKey().matches("[A-Za-z0-9_]{1,32}")) {
                throw TeacherProblems.validation("criteria." + e.getKey(), "UnknownCriterion", "Not a valid criterion code.");
            }
            Double v = e.getValue();
            double min = c != null && c.min() != null ? c.min() : 0;
            double max = c != null && c.max() != null ? c.max() : MAX_SCORE;
            if (v == null || v.isNaN() || v < min || v > max) {
                throw TeacherProblems.validation("criteria." + e.getKey(), "OutOfRange", "The score must be between " + min + " and " + max + ".");
            }
            out.put(e.getKey(), v);
        }
        if (!byCode.isEmpty() && !given.keySet().containsAll(byCode.keySet())) {
            throw TeacherProblems.validation("criteria", "Incomplete", "Give a score for every criterion: " + String.join(", ", byCode.keySet()) + ".");
        }
        return out;
    }

    private static Map<String, Double> numbers(JsonNode criteria) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (criteria != null) {
            criteria.fields().forEachRemaining(e -> {
                if (e.getValue().isNumber()) {
                    out.put(e.getKey(), e.getValue().asDouble());
                }
            });
        }
        return out;
    }

    static String taskText(JsonNode content) {
        StringBuilder out = new StringBuilder();
        for (String field : new String[] {"prompt", "topic", "title", "instructions"}) {
            if (content.path(field).isTextual()) {
                out.append(content.path(field).asText()).append('\n');
            }
        }
        if (content.path("bulletPoints").isArray()) {
            content.path("bulletPoints").forEach(b -> out.append("- ").append(b.asText()).append('\n'));
        }
        return out.length() == 0 ? "" : out.toString().strip();
    }

    static String answerText(JsonNode answer) {
        if (answer == null || answer.isNull()) {
            return "";
        }
        if (answer.isTextual()) {
            return answer.asText();
        }
        return answer.isObject() ? answer.path("text").asText(answer.path("answer").asText("")) : "";
    }
}
