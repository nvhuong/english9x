package com.english9x.grading.ai;

import com.english9x.catalog.api.GradingPolicies;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.content.media.api.StagedFiles;
import com.english9x.domain.grading.GradingMode;
import com.english9x.grading.ai.AiStore.Attempt;
import com.english9x.grading.ai.AiStore.Item;
import com.english9x.grading.ai.AiStore.Skill;
import com.english9x.grading.ai.api.AiGrader;
import com.english9x.grading.ai.api.AiGradingException;
import com.english9x.grading.ai.api.AiGradingException.Kind;
import com.english9x.grading.ai.api.AiResult;
import com.english9x.grading.ai.api.Response;
import com.english9x.grading.ai.api.SkillVersion;
import com.english9x.grading.ai.api.Task;
import com.english9x.grading.auto.ScoreAnnouncer;
import com.english9x.grading.db.GraderDatabase;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.OrgContextDeniedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI grading of the Writing and Speaking of a submitted attempt (spec 10.3; F34, F37).
 *
 * <p><b>Nothing the caller says is believed.</b> The ids are a hint (an event, a job payload): they choose the session of the grader, whose policies show only the SUBMITTED
 * attempt of exactly that person and organisation. The owner, the exam, the test version and the answers are read back from rows.
 *
 * <p><b>Not before the late-sync grace</b>, like the automatic scoring; the job is queued for the end of it.
 *
 * <p><b>The grading skill is read as {@code e9x_grader}</b> (decision of P2.1-fix1 M3): the published skill of English9x or of the attempt's organisation. The learner's own role
 * reads none.
 *
 * <p><b>Idempotent, resumable.</b> Each response's evaluation is stored as soon as the model answered ({@code ai_evaluations}, one per question), so a retry after a failure
 * asks only for what is missing. The scores and the DONE state are written together; a second run finds DONE and only repeats the (idempotent) announcement.
 *
 * <p><b>Mode.</b> TEACHER: nothing here. AI: the AI score is OFFICIAL. BOTH: PRELIMINARY (the teacher's will replace it). The emergency limit
 * {@code e9x.grading.ai.per-user-daily-limit} (0 = off) sends the attempt to the teachers' queue (state TEACHER_QUEUE) instead of the AI, counted over the last 24 hours in the
 * attempt's context.
 */
@Component
class AiGradingService {

    enum Outcome { NOT_ELIGIBLE, GRACE_NOT_OVER, NOT_AI, TEACHER_QUEUE, DONE, ALREADY_DONE }

    private static final Logger LOG = LoggerFactory.getLogger(AiGradingService.class);

    private final GraderDatabase grader;
    private final AiStore store;
    private final AiGrader ai;
    private final QuestionTypes types;
    private final GradingPolicies policies;
    private final StagedFiles staged;
    private final ScoreAnnouncer announcer;
    private final TransactionTemplate primary;
    private final Clock clock;
    private final ObjectMapper json;
    private final Duration grace;
    private final int dailyLimit;
    private final BigDecimal priceInput;
    private final BigDecimal priceOutput;

    AiGradingService(GraderDatabase grader, AiStore store, AiGrader ai, QuestionTypes types, GradingPolicies policies, StagedFiles staged, ScoreAnnouncer announcer,
            PlatformTransactionManager transactions, Clock clock, ObjectMapper json, @Value("${e9x.exam.late-sync-grace}") Duration grace,
            @Value("${e9x.grading.ai.per-user-daily-limit:0}") int dailyLimit, @Value("${e9x.gemini.price-per-1k-input:0}") BigDecimal priceInput,
            @Value("${e9x.gemini.price-per-1k-output:0}") BigDecimal priceOutput) {
        this.grader = grader;
        this.store = store;
        this.ai = ai;
        this.types = types;
        this.policies = policies;
        this.staged = staged;
        this.announcer = announcer;
        this.primary = new TransactionTemplate(transactions);
        this.clock = clock;
        this.json = json;
        this.grace = grace;
        this.dailyLimit = dailyLimit;
        this.priceInput = priceInput;
        this.priceOutput = priceOutput;
    }

    Optional<Attempt> find(UUID attemptId, UUID userId, UUID orgId) {
        return grader.read(userId, orgId, j -> store.attempt(j, attemptId));
    }

    Instant gradeAfter(Attempt a) {
        return a.submittedAt().plus(grace);
    }

    /** True when the attempt sat a Writing or Speaking section (the only ones this job has work for). */
    boolean hasWritingOrSpeaking(Attempt a) {
        return grader.read(a.userId(), a.orgId(), j -> store.sectionSkills(j, a.id()).values().stream().anyMatch(s -> "WRITING".equals(s) || "SPEAKING".equals(s)));
    }

    /** @throws AiGradingException when the model could not be had; the evaluations stored so far stay */
    Outcome grade(UUID attemptId, UUID userIdHint, UUID orgIdHint) {
        Optional<Attempt> found = find(attemptId, userIdHint, orgIdHint);
        if (found.isEmpty()) {
            return Outcome.NOT_ELIGIBLE;
        }
        Attempt a = found.get();
        if (clock.instant().isBefore(gradeAfter(a))) {
            return Outcome.GRACE_NOT_OVER;
        }
        Optional<String> state = grader.read(a.userId(), a.orgId(), j -> store.state(j, a.id()));
        if (state.isPresent() && !"AI_FAILED".equals(state.get())) {
            if ("DONE".equals(state.get())) {
                announcer.announce(a.userId(), a.orgId(), a.id());
                return Outcome.ALREADY_DONE;
            }
            return Outcome.TEACHER_QUEUE;
        }
        GradingMode mode = mode(a);
        if (mode == GradingMode.TEACHER) {
            return Outcome.NOT_AI;
        }
        Map<String, String> skillOfSection = new LinkedHashMap<>();
        List<Item> items = new ArrayList<>();
        grader.read(a.userId(), a.orgId(), j -> {
            skillOfSection.putAll(store.sectionSkills(j, a.id()));
            for (Item i : store.items(j, a.id(), a.testVersionId())) {
                if (i.questionId() != null && skillOfSection.get(i.sectionCode()) != null && !types.get(i.typeCode()).kind().autoScored()) {
                    items.add(i);
                }
            }
            return null;
        });
        if (items.isEmpty()) {
            return Outcome.NOT_AI;
        }
        if (dailyLimit > 0 && state.isEmpty()) {
            int used = grader.read(a.userId(), a.orgId(), j -> store.aiAttemptsSince(j, a.userId(), clock.instant().minus(Duration.ofHours(24))));
            if (used >= dailyLimit) {
                grader.write(a.userId(), a.orgId(), j -> store.routeToTeachers(j, a));
                LOG.info("attempt {}: the emergency daily AI limit is reached for this person; routed to the teachers' queue", a.id());
                return Outcome.TEACHER_QUEUE;
            }
        }

        Map<java.util.UUID, Double> bands = new LinkedHashMap<>(grader.read(a.userId(), a.orgId(), j -> store.evaluated(j, a.id())));
        Map<String, Optional<Skill>> skills = new LinkedHashMap<>();
        for (Item item : items) {
            if (bands.containsKey(item.questionId())) {
                continue; // evaluated by an earlier run
            }
            bands.put(item.questionId(), evaluate(a, item, skills));
        }

        String kind = mode == GradingMode.BOTH ? "PRELIMINARY" : "OFFICIAL";
        Map<String, double[]> perSkill = new LinkedHashMap<>(); // skill -> {sum of band * points, sum of points}
        for (Item item : items) {
            double[] acc = perSkill.computeIfAbsent(skillOfSection.get(item.sectionCode()), k -> new double[2]);
            acc[0] += bands.get(item.questionId()) * item.points();
            acc[1] += item.points();
        }
        grader.write(a.userId(), a.orgId(), j -> {
            perSkill.forEach((skill, acc) -> store.insertScore(j, a, skill, kind, Bands.nearestHalf(acc[1] == 0 ? 0 : acc[0] / acc[1])));
            store.markDone(j, a);
            return null;
        });
        announcer.announce(a.userId(), a.orgId(), a.id());
        return Outcome.DONE;
    }

    /** Asks the model about one response (or scores a blank one 0 without asking) and stores the evaluation. @return its band */
    private double evaluate(Attempt a, Item item, Map<String, Optional<Skill>> skills) {
        Optional<Response> response = response(item);
        if (response.isEmpty()) {
            grader.write(a.userId(), a.orgId(), j -> {
                store.insertEvaluation(j, a, item.questionId(), null, "none", 0, 0, BigDecimal.ZERO, 0, json.createObjectNode().put("empty", true));
                return null;
            });
            return 0;
        }
        Skill skill = skills.computeIfAbsent(item.typeCode(), t -> grader.read(a.userId(), a.orgId(), j -> store.skill(j, t)))
                .orElseThrow(() -> new AiGradingException(Kind.PERMANENT, "no published grading skill is readable for " + item.typeCode()));
        AiResult result = ai.grade(new SkillVersion(skill.versionId(), skill.rubric(), skill.criteria(), skill.promptTemplate(), skill.samples(), skill.resultSchema()),
                new Task(item.typeCode(), taskText(item.content())), response.get());
        BigDecimal cost = BigDecimal.valueOf(result.inputTokens()).multiply(priceInput).add(BigDecimal.valueOf(result.outputTokens()).multiply(priceOutput))
                .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
        grader.write(a.userId(), a.orgId(), j -> {
            store.insertEvaluation(j, a, item.questionId(), skill.versionId(), result.model(), result.inputTokens(), result.outputTokens(), cost, result.band(), result.result());
            return null;
        });
        return result.band();
    }

    private Optional<Response> response(Item item) {
        if (item.recordingPath() != null) {
            Path tmp = null;
            try {
                tmp = Files.createTempFile("e9x-ai-", ".webm");
                staged.get(item.recordingPath(), tmp);
                byte[] bytes = Files.readAllBytes(tmp);
                return bytes.length == 0 ? Optional.empty() : Optional.of(new Response.Audio(bytes, item.recordingMime()));
            } catch (IOException e) {
                throw new AiGradingException(Kind.TRANSIENT, "the recording could not be read", e);
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
        JsonNode answer = item.answer();
        String text = "";
        if (answer != null && answer.isTextual()) {
            text = answer.asText();
        } else if (answer != null && answer.isObject()) {
            text = answer.path("text").asText(answer.path("answer").asText(""));
        }
        return text.isBlank() ? Optional.empty() : Optional.of(new Response.Text(text));
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
        return out.length() == 0 ? content.toString() : out.toString().strip();
    }

    private GradingMode mode(Attempt a) {
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(a.userId(), a.orgId())) {
            return primary.execute(st -> policies.resolve(a.examCode(), Optional.ofNullable(a.orgId()), Optional.of(a.testVersionId())));
        } catch (RuntimeException e) {
            if (!isOrgDenied(e)) {
                throw e;
            }
        }
        // the member left the organisation since: its policy is not theirs to read, the policy of the exam decides (the AI itself needs no membership)
        LOG.warn("attempt {}: the learner is no longer a member of the organisation of the attempt; the policy of the exam decides the grading mode", a.id());
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(a.userId(), null)) {
            return primary.execute(st -> policies.resolve(a.examCode(), Optional.empty(), Optional.of(a.testVersionId())));
        }
    }

    private static boolean isOrgDenied(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof OrgContextDeniedException) {
                return true;
            }
        }
        return false;
    }
}
