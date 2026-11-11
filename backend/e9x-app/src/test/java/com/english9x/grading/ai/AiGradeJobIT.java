package com.english9x.grading.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.exam.api.events.AttemptSubmitted;
import com.english9x.exam.internal.ExamIT;
import com.english9x.grading.ai.api.AiGrader;
import com.english9x.grading.ai.api.AiGradingException;
import com.english9x.grading.ai.api.AiGradingException.Kind;
import com.english9x.grading.ai.api.AiResult;
import com.english9x.grading.ai.api.Response;
import com.english9x.grading.ai.api.SkillVersion;
import com.english9x.grading.ai.api.Task;
import com.english9x.grading.api.events.AttemptScored;
import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code ai_grade} job through the real stack (real listener, job queue, grader login, scores, notifications) with a scripted {@link AiGrader} in place of Gemini (the HTTP side is
 * {@link GeminiAiGraderTest}). The worker is off: the test runs the job itself, with the clock where it wants it.
 */
@Import(AiGradeJobIT.Fakes.class)
class AiGradeJobIT extends ExamIT {

    static class Scripted implements AiGrader {
        final AtomicInteger calls = new AtomicInteger();
        volatile AiGradingException failure;

        @Override
        public AiResult grade(SkillVersion skill, Task task, Response response) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            ObjectNode result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("TA", 6).put("CC", 7);
            return new AiResult(result, 6.5, "gemini-test", 1000, 500);
        }
    }

    static class Scored {
        final List<AttemptScored> seen = new CopyOnWriteArrayList<>();

        @TransactionalEventListener
        void on(AttemptScored e) {
            seen.add(e);
        }

        long count(UUID attempt) {
            return seen.stream().filter(e -> e.attemptId().equals(attempt)).count();
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        Scripted scriptedGrader() {
            return new Scripted();
        }

        @Bean
        Scored scoredEvents() {
            return new Scored();
        }
    }

    @Autowired Scripted gemini;
    @Autowired Scored scored;
    @Autowired AiGradeJobHandler handler;
    @Autowired AiGradingService service;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;

    private static final Duration GRACE = Duration.ofMinutes(2);

    @BeforeEach
    void reset() {
        gemini.calls.set(0);
        gemini.failure = null;
    }

    @AfterEach
    void restoreLimit() {
        ReflectionTestUtils.setField(service, "dailyLimit", 0);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------------------------

    private record Q(UUID question, UUID version) {}

    private record Fx(UUID version, Q w, Q s) {}

    private Q question(String type, UUID author) {
        UUID q = UUID.randomUUID();
        UUID v = UUID.randomUUID();
        su.update("insert into questions(id,owner_org_id,exam_code,type_code,created_by) values (?,null,'IELTS',?,?)", q, type, author);
        su.update("insert into question_versions(id,question_id,owner_org_id,exam_code,type_code,version_no,status,content,answer_key,points,created_by) "
                + "values (?,?,null,'IELTS',?,1,'PUBLISHED','{\"prompt\":\"Discuss both views.\"}'::jsonb,'{}'::jsonb,?,?)", v, q, type, "IELTS_W_TASK2".equals(type) ? 2 : 1, author);
        return new Q(q, v);
    }

    /** A PUBLISHED IELTS test with a Writing and a Speaking section of one question each. */
    private Fx test() {
        UUID author = user("ai-author");
        UUID testId = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        su.update("insert into tests(id,owner_org_id,exam_code,variant,created_by) values (?,null,'IELTS','AC',?)", testId, author);
        su.update("insert into test_versions(id,test_id,owner_org_id,exam_code,variant,version_no,status,title,created_by) values (?,?,null,'IELTS','AC',1,'PUBLISHED','AI fixture',?)",
                version, testId, author);
        Fx fx = new Fx(version, question("IELTS_W_TASK2", author), question("IELTS_S_PART1", author));
        for (String t : new String[] {"test_parts", "test_part_groups", "question_group_items"}) {
            su.execute("alter table " + t + " disable trigger user");
        }
        String[] sections = {"WRITING", "SPEAKING"};
        Q[] members = {fx.w(), fx.s()};
        for (int i = 0; i < 2; i++) {
            UUID group = UUID.randomUUID();
            UUID groupVersion = UUID.randomUUID();
            su.update("insert into question_groups(id,owner_org_id,exam_code,created_by) values (?,null,'IELTS',?)", group, author);
            su.update("insert into question_group_versions(id,group_id,owner_org_id,exam_code,version_no,status,content,created_by) values (?,?,null,'IELTS',1,'PUBLISHED','{}'::jsonb,?)", groupVersion, group, author);
            su.update("insert into question_group_items(group_version_id,position,question_version_id,owner_org_id,exam_code) values (?,1,?,null,'IELTS')", groupVersion, members[i].version());
            su.update("insert into test_parts(test_version_id,position,section_code,duration_sec,owner_org_id,exam_code) values (?,?,?,600,null,'IELTS')", version, i + 1, sections[i]);
            su.update("insert into test_part_groups(test_version_id,part_position,position,group_version_id,owner_org_id,exam_code) values (?,?,1,?,null,'IELTS')", version, i + 1, groupVersion);
        }
        for (String t : new String[] {"test_parts", "test_part_groups", "question_group_items"}) {
            su.execute("alter table " + t + " enable trigger user");
        }
        return fx;
    }

    /** English9x's PUBLISHED grading skill of the type, unless the shared database has one. */
    private void publishedSkill(String type) {
        if (su.queryForObject("select count(*) from grading_skill_versions v join grading_skills s on s.id = v.grading_skill_id "
                + "where s.owner_org_id is null and s.question_type_code = ? and v.status = 'PUBLISHED'", Integer.class, type) > 0) {
            return;
        }
        UUID author = user("ai-author");
        UUID skill = su.queryForList("select id from grading_skills where owner_org_id is null and question_type_code = ?", UUID.class, type).stream().findFirst().orElseGet(() -> {
            UUID id = UUID.randomUUID();
            su.update("insert into grading_skills(id,owner_org_id,exam_code,question_type_code) values (?,null,'IELTS',?)", id, type);
            return id;
        });
        int no = su.queryForObject("select coalesce(max(version_no),0)+1 from grading_skill_versions where grading_skill_id = ?", Integer.class, skill);
        UUID version = UUID.randomUUID();
        su.update("insert into grading_skill_versions(id,grading_skill_id,owner_org_id,exam_code,version_no,status,rubric,criteria,prompt_template,samples,result_schema,created_by) "
                + "values (?,?,null,'IELTS',?,'DRAFT','rubric','[{\"code\":\"TA\",\"name\":\"Task\",\"min\":0,\"max\":9,\"step\":0.5},{\"code\":\"CC\",\"name\":\"Coherence\",\"min\":0,\"max\":9,\"step\":0.5}]'::jsonb,"
                + "'{{rubric}} {{samples}} {{task}} {{response}}','[]'::jsonb,'{\"type\":\"object\",\"properties\":{\"TA\":{\"type\":\"number\"},\"CC\":{\"type\":\"number\"}},\"required\":[\"TA\",\"CC\"]}'::jsonb,?)",
                version, skill, no, author);
        su.execute("alter table grading_skill_versions disable trigger user");
        su.update("update grading_skill_versions set status = 'PUBLISHED' where id = ?", version);
        su.execute("alter table grading_skill_versions enable trigger user");
    }

    private UUID submitted(Fx fx, UUID learner, UUID org, Instant at, String essay) {
        UUID attempt = UUID.randomUUID();
        su.update("insert into exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,submitted_at,submit_reason,interrupted) "
                + "values (?,?,?,?,'SUBMITTED','IELTS',2,?,'LEARNER',false)", attempt, learner, org, fx.version(), java.sql.Timestamp.from(at));
        String[] sections = {"WRITING", "SPEAKING"};
        for (int i = 0; i < 2; i++) {
            su.update("insert into attempt_sections(attempt_id,position,user_id,section_code,skill,duration_sec,starts_at) values (?,?,?,?,?,600,?)", attempt, i, learner,
                    sections[i], sections[i], java.sql.Timestamp.from(at.minusSeconds(600)));
        }
        su.update("insert into attempt_questions(attempt_id,question_id,user_id,question_version_id,section_code) values (?,?,?,?,'WRITING')", attempt, fx.w().question(), learner, fx.w().version());
        su.update("insert into attempt_questions(attempt_id,question_id,user_id,question_version_id,section_code) values (?,?,?,?,'SPEAKING')", attempt, fx.s().question(), learner, fx.s().version());
        if (essay != null) {
            su.update("insert into responses(attempt_id,question_id,user_id,answer,answered_at,saved_at) values (?,?,?,?::jsonb,?,?)", attempt, fx.w().question(), learner,
                    "\"" + essay + "\"", java.sql.Timestamp.from(at.minusSeconds(60)), java.sql.Timestamp.from(at.minusSeconds(60)));
        }
        return attempt;
    }

    /** What the job holds once the listener queued it; the job of an attempt whose event the test published. */
    private Job queued(UUID attempt, UUID learner, Fx fx, Optional<UUID> org, Instant at) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> events.publishEvent(new AttemptSubmitted(attempt, learner, fx.version(), org, at, "LEARNER", false)));
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(
                () -> su.queryForObject("select count(*) from jobs where type = 'ai_grade' and payload->>'attemptId' = ?", Integer.class, attempt.toString()) > 0);
        Map<String, Object> row = su.queryForMap("select id, payload::text as p, run_at from jobs where type = 'ai_grade' and payload->>'attemptId' = ? limit 1", attempt.toString());
        try {
            assertEquals(at.plus(GRACE).toEpochMilli(), ((java.sql.Timestamp) row.get("run_at")).toInstant().toEpochMilli(), "queued for the end of the grace");
            return new Job((UUID) row.get("id"), "ai_grade", JSON.readTree((String) row.get("p")), 1, Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Job forged(UUID attempt, UUID learner, UUID org) {
        ObjectNode p = JSON.createObjectNode().put("attemptId", attempt.toString()).put("userId", learner.toString());
        if (org != null) {
            p.put("orgId", org.toString());
        }
        return new Job(UUID.randomUUID(), "ai_grade", p, 1, Instant.now());
    }

    private String state(UUID attempt) {
        return su.queryForList("select state from ai_gradings where attempt_id = ?", String.class, attempt).stream().findFirst().orElse(null);
    }

    private int notifications(UUID user, String type, UUID attempt) {
        return su.queryForObject("select count(*) from notifications where user_id = ? and event_type = ? and dedupe_key like ?", Integer.class, user, type, "%" + attempt);
    }

    // ---- the tests --------------------------------------------------------------------------------------------------------------------

    @Test
    void gradesWritingAndSpeakingAsTheGraderAndAnnounces() {
        publishedSkill("IELTS_W_TASK2");
        Fx fx = test();
        UUID learner = user("ai-learner");
        Instant at = Instant.now();
        clock.set(at);
        UUID attempt = submitted(fx, learner, null, at, "An essay.");
        Job job = queued(attempt, learner, fx, Optional.empty(), at);

        clock.set(at.plus(GRACE).minusSeconds(1));
        assertFalse(handler.handle(job).isSuccess(), "the grace is not over");
        assertEquals(0, gemini.calls.get());

        clock.set(at.plus(GRACE).plusSeconds(1));
        assertTrue(handler.handle(job).isSuccess());
        assertEquals(1, gemini.calls.get(), "the essay is asked about; the missing recording is a blank answer scored 0 without a call");
        Map<String, Map<String, Object>> rows = su.queryForList("select skill, kind, source, scaled from scores where attempt_id = ?", attempt).stream()
                .collect(java.util.stream.Collectors.toMap(r -> (String) r.get("skill"), r -> r));
        assertEquals(6.5, ((BigDecimal) rows.get("WRITING").get("scaled")).doubleValue());
        assertEquals("AI", rows.get("WRITING").get("source"));
        assertEquals("OFFICIAL", rows.get("WRITING").get("kind"), "mode AI: the AI score is the official one");
        assertEquals(0.0, ((BigDecimal) rows.get("SPEAKING").get("scaled")).doubleValue());
        assertEquals("DONE", state(attempt));
        Map<String, Object> ev = su.queryForMap("select model, input_tokens, output_tokens, cost_vnd, skill_version_id from ai_evaluations where attempt_id = ? and model <> 'none'", attempt);
        assertEquals("gemini-test", ev.get("model"));
        assertEquals(1000, ev.get("input_tokens"));
        assertEquals(7.5 + 31.25, ((BigDecimal) ev.get("cost_vnd")).doubleValue(), 0.0001, "1000 in x 7.5 + 500 out x 62.5 per 1k");
        assertTrue(ev.get("skill_version_id") != null);
        // the grading record is the automatic scoring's: until it runs, nothing is announced; the AI's scores are already there for it
        assertEquals(0, scored.count(attempt));

        assertTrue(handler.handle(job).isSuccess(), "at least once");
        assertEquals(1, gemini.calls.get(), "a second run asks nothing");
        assertEquals(2, su.queryForObject("select count(*) from scores where attempt_id = ?", Integer.class, attempt));
    }

    @Test
    void exhaustedRetriesMarksAiFailedAndNotifiesAdmin() {
        publishedSkill("IELTS_W_TASK2");
        Fx fx = test();
        UUID learner = user("ai-learner-f");
        UUID admin = user("ai-admin");
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,null,null,'SYS_ADMIN')", admin);
        Instant at = Instant.now();
        clock.set(at);
        UUID attempt = submitted(fx, learner, null, at, "An essay.");
        Job job = queued(attempt, learner, fx, Optional.empty(), at);
        clock.set(at.plus(GRACE).plusSeconds(1));

        gemini.failure = new AiGradingException(Kind.TRANSIENT, "Gemini answered HTTP 500", new java.net.ConnectException("refused"));
        JobResult run = handler.handle(job);
        assertFalse(run.isSuccess(), "the queue retries after e9x.grading.ai.retry-delays");
        assertEquals("AiGradingException(TRANSIENT): Gemini answered HTTP 500 <- ConnectException", run.error(), "the reason carries the root cause");
        assertEquals(null, state(attempt), "not given up yet");

        handler.onExhausted(job, run.error());
        assertEquals("AI_FAILED", state(attempt));
        assertEquals(1, notifications(admin, "AI_GRADING_FAILED", attempt));
        assertEquals(0, su.queryForObject("select count(*) from scores where attempt_id = ?", Integer.class, attempt), "the learner has no score: still 'in progress'");
        handler.onExhausted(job, run.error()); // at least once
        assertEquals(1, notifications(admin, "AI_GRADING_FAILED", attempt), "told once");
        assertEquals(1, su.queryForObject("select count(*) from ai_gradings where attempt_id = ?", Integer.class, attempt));

        // a late run that overran its deadline and then succeeded: the work is done, so a giving-up for it changes nothing
        gemini.failure = null;
        assertTrue(handler.handle(job).isSuccess());
        assertEquals("DONE", state(attempt), "AI_FAILED becomes DONE when the work gets done");
        handler.onExhausted(job, "lease expired after the last attempt (4 of 4); not run again");
        assertEquals("DONE", state(attempt));
        UUID attempt2 = submitted(fx, learner, null, at, "Another essay.");
        Job job2 = forged(attempt2, learner, null);
        assertTrue(handler.handle(job2).isSuccess());
        handler.onExhausted(job2, "lease expired after the last attempt (4 of 4); not run again");
        assertEquals("DONE", state(attempt2));
        assertEquals(0, notifications(admin, "AI_GRADING_FAILED", attempt2), "no alert for work that is done");
    }

    @Test
    void aMissingSkillFailsTheJobAndGivesUpToo() {
        Fx fx = test();
        UUID learner = user("ai-learner-s");
        Instant at = Instant.now().minus(Duration.ofHours(1));
        clock.set(Instant.now());
        UUID attempt = submitted(fx, learner, null, at, "An essay.");
        su.execute("alter table grading_skill_versions disable trigger user");
        try {
            su.update("update grading_skill_versions set status = 'ARCHIVED' where status = 'PUBLISHED' and exam_code = 'IELTS' and grading_skill_id in "
                    + "(select id from grading_skills where question_type_code = 'IELTS_W_TASK2')");
            JobResult r = handler.handle(forged(attempt, learner, null));
            assertTrue(r.isSuccess(), "a PERMANENT failure is not retried: the job ends here, in AI_FAILED");
            assertEquals("AI_FAILED", state(attempt));
            assertTrue(su.queryForObject("select error from ai_gradings where attempt_id = ?", String.class, attempt).contains("no published grading skill"));
            assertEquals(0, gemini.calls.get());
        } finally {
            su.update("update grading_skill_versions set status = 'PUBLISHED' where status = 'ARCHIVED' and exam_code = 'IELTS' and grading_skill_id in "
                    + "(select id from grading_skills where question_type_code = 'IELTS_W_TASK2')");
            su.execute("alter table grading_skill_versions enable trigger user");
        }
    }

    @Test
    void lateSuccessBeforeGivingUpSendsNoFalseAlert() {
        publishedSkill("IELTS_W_TASK2");
        Fx fx = test();
        UUID learner = user("ai-learner-r");
        UUID admin = user("ai-admin-r");
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,null,null,'SYS_ADMIN')", admin);
        Instant at = Instant.now();
        clock.set(at.plus(GRACE).plusSeconds(1));
        UUID attempt = submitted(fx, learner, null, at, "An essay.");
        Job job = forged(attempt, learner, null);
        assertTrue(handler.handle(job).isSuccess());
        assertEquals("DONE", state(attempt));
        // onExhausted read "no state", then the late run wrote DONE before markFailed: record() is that second half
        handler.record(job, service.find(attempt, learner, null).orElseThrow(), "lease expired");
        assertEquals("DONE", state(attempt));
        assertEquals(0, notifications(admin, "AI_GRADING_FAILED", attempt), "markFailed was a no-op: nobody is told of a failure that did not happen");
    }

    @Test
    void dailyLimitZeroMeansUnlimited() {
        publishedSkill("IELTS_W_TASK2");
        Fx fx = test();
        UUID learner = user("ai-learner-u");
        Instant at = Instant.now().minus(Duration.ofHours(1));
        clock.set(Instant.now());
        ReflectionTestUtils.setField(service, "dailyLimit", 0);
        for (int i = 0; i < 4; i++) {
            UUID attempt = submitted(fx, learner, null, at, "Essay " + i);
            assertTrue(handler.handle(forged(attempt, learner, null)).isSuccess());
            assertEquals("DONE", state(attempt), "attempt " + i + " went to the AI");
        }
        assertEquals(4, gemini.calls.get());
    }

    @Test
    void dailyLimitPositiveRoutesToTeacherQueue() {
        publishedSkill("IELTS_W_TASK2");
        Fx fx = test();
        UUID learner = user("ai-learner-l");
        Instant at = Instant.now().minus(Duration.ofHours(1));
        clock.set(Instant.now());
        ReflectionTestUtils.setField(service, "dailyLimit", 2);
        UUID a1 = submitted(fx, learner, null, at, "One");
        UUID a2 = submitted(fx, learner, null, at, "Two");
        UUID a3 = submitted(fx, learner, null, at, "Three");
        assertTrue(handler.handle(forged(a1, learner, null)).isSuccess());
        assertTrue(handler.handle(forged(a2, learner, null)).isSuccess());
        assertEquals("DONE", state(a1));
        assertEquals("DONE", state(a2));
        int calls = gemini.calls.get();
        JobResult third = handler.handle(forged(a3, learner, null));
        assertTrue(third.isSuccess(), "the learner is not refused and the job does not fail");
        assertEquals("TEACHER_QUEUE", state(a3), "routed to the teachers");
        assertEquals(calls, gemini.calls.get(), "the AI was not asked");
        assertEquals(0, su.queryForObject("select count(*) from scores where attempt_id = ?", Integer.class, a3));
        assertTrue(handler.handle(forged(a3, learner, null)).isSuccess());
        assertEquals("TEACHER_QUEUE", state(a3), "stays there");
        // another person is not affected by this one's count
        UUID other = user("ai-learner-l2");
        UUID b = submitted(fx, other, null, at, "Mine");
        assertTrue(handler.handle(forged(b, other, null)).isSuccess());
        assertEquals("DONE", state(b));
    }

    @Test
    void aMemberWhoLeftTheOrganisationIsStillGraded() {
        publishedSkill("IELTS_W_TASK2");
        Fx fx = test();
        UUID learner = user("ai-learner-r");
        UUID org = UUID.randomUUID();
        su.update("insert into organizations(id,name,type) values (?,?,'CENTER')", org, "Left " + org.toString().substring(0, 8));
        Instant at = Instant.now().minus(Duration.ofHours(1));
        clock.set(Instant.now());
        UUID attempt = submitted(fx, learner, org, at, "An essay.");
        assertTrue(handler.handle(forged(attempt, learner, org)).isSuccess(), "no membership, no failure");
        assertEquals("DONE", state(attempt));
        assertEquals(1, gemini.calls.get());
    }

    @Test
    void theEventAndTheJobAreOnlyHints() {
        publishedSkill("IELTS_W_TASK2");
        Fx fx = test();
        UUID learner = user("ai-learner-h");
        Instant at = Instant.now().minus(Duration.ofHours(1));
        clock.set(Instant.now());
        UUID attempt = submitted(fx, learner, null, at, "An essay.");
        // a forged job: another person, another organisation, no attempt
        assertTrue(handler.handle(forged(attempt, user("thief"), null)).isSuccess());
        assertTrue(handler.handle(forged(attempt, learner, UUID.randomUUID())).isSuccess());
        assertTrue(handler.handle(forged(UUID.randomUUID(), learner, null)).isSuccess());
        assertEquals(0, gemini.calls.get());
        assertEquals(null, state(attempt));
        // forged events queue nothing
        Instant now = Instant.now();
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            events.publishEvent(new AttemptSubmitted(attempt, user("thief2"), fx.version(), Optional.empty(), now, "LEARNER", false));
            events.publishEvent(new AttemptSubmitted(attempt, learner, UUID.randomUUID(), Optional.empty(), now, "LEARNER", false));
        });
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> su.queryForObject(
                "select count(*) from event_publication where listener_id like '%AiGradeScheduler%' and completion_date is null", Integer.class) == 0);
        assertEquals(0, su.queryForObject("select count(*) from jobs where type = 'ai_grade' and payload->>'attemptId' = ?", Integer.class, attempt.toString()));
    }

    @Test
    void aPreliminaryScoreNotifiesTheLearnerOnceAndAForgedEventNobody() {
        UUID learner = user("ai-learner-n");
        Fx fx = test();
        Instant at = Instant.now().minus(Duration.ofHours(1));
        UUID attempt = submitted(fx, learner, null, at, "An essay.");
        // nothing PRELIMINARY exists: a forged AttemptScored(official=false) notifies nobody
        new TransactionTemplate(transactions).executeWithoutResult(s -> events.publishEvent(new AttemptScored(attempt, learner, Map.of("WRITING", 6.0), false)));
        su.update("insert into scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,scaled) values (?,?,null,'WRITING','PRELIMINARY','AI',6,6)", attempt, learner);
        for (int i = 0; i < 2; i++) { // delivered twice
            new TransactionTemplate(transactions).executeWithoutResult(s -> events.publishEvent(new AttemptScored(attempt, learner, Map.of("WRITING", 6.0), false)));
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> su.queryForObject(
                "select count(*) from event_publication where listener_id like '%AiNotifications%' and completion_date is null", Integer.class) == 0);
        assertEquals(1, notifications(learner, "SCORE_PRELIMINARY", attempt));
    }
}
