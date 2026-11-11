package com.english9x.grading.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.exam.internal.ExamIT;
import com.english9x.grading.ai.AiStore.Attempt;
import com.english9x.grading.api.events.AttemptScored;
import com.english9x.grading.auto.ScoreAnnouncer;
import com.english9x.platform.jobs.api.JobQueue;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The teachers' queue (F35) and "Both" mode (F36) through the real stack: the real HTTP routes, Row-Level Security on the request connection, the grader login in the learner's
 * context, the notification framework, the real worker for {@code release_expired_claim} (the clock is the test's). Attempts are written as the superuser, the way the exam room
 * would have left them; the AI's scores of "Both" are written as the AI job would.
 */
@Import(TeacherQueueIT.Fakes.class)
@TestPropertySource(properties = {"app.worker.enabled=true", "e9x.jobs.poll-interval=100ms", "e9x.jobs.recurring.enabled=false"})
class TeacherQueueIT extends ExamIT {

    static class Scored {
        final List<AttemptScored> seen = new CopyOnWriteArrayList<>();

        @TransactionalEventListener
        void on(AttemptScored e) {
            seen.add(e);
        }

        List<AttemptScored> of(UUID attempt) {
            return seen.stream().filter(e -> e.attemptId().equals(attempt)).toList();
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        Scored scoredEvents() {
            return new Scored();
        }
    }

    @Autowired Scored scored;
    @Autowired GradingQueueService queue;
    @Autowired ScoreAnnouncer announcer;
    @Autowired JobQueue jobs;
    @Autowired GradingStore gradingStore;
    @Autowired com.english9x.grading.db.GraderDatabase graderDb;
    @Autowired PlatformTransactionManager tm;

    private static final Duration GRACE = Duration.ofMinutes(2);

    // ---- fixtures ---------------------------------------------------------------------------------------------------------------------

    private record Q(UUID question, UUID version) {}

    private record Fx(UUID testId, UUID version, Q w, Q s) {}

    private Q question(String type, UUID author) {
        UUID q = UUID.randomUUID();
        UUID v = UUID.randomUUID();
        su.update("insert into questions(id,owner_org_id,exam_code,type_code,created_by) values (?,null,'IELTS',?,?)", q, type, author);
        su.update("insert into question_versions(id,question_id,owner_org_id,exam_code,type_code,version_no,status,content,answer_key,points,created_by) "
                + "values (?,?,null,'IELTS',?,1,'PUBLISHED','{\"prompt\":\"Discuss both views.\"}'::jsonb,'{}'::jsonb,1,?)", v, q, type, author);
        return new Q(q, v);
    }

    /** A PUBLISHED IELTS test with a Writing and a Speaking section of one question each (the fixture of the AI tests). */
    private Fx test() {
        UUID author = user("gq-author");
        UUID testId = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        su.update("insert into tests(id,owner_org_id,exam_code,variant,created_by) values (?,null,'IELTS','AC',?)", testId, author);
        su.update("insert into test_versions(id,test_id,owner_org_id,exam_code,variant,version_no,status,title,created_by) values (?,?,null,'IELTS','AC',1,'PUBLISHED','Queue fixture',?)",
                version, testId, author);
        Fx fx = new Fx(testId, version, question("IELTS_W_TASK2", author), question("IELTS_S_PART1", author));
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

    /** English9x's PUBLISHED Writing skill (criteria TA and CC, 0 to 9), unless the shared database has one. */
    private void publishedWritingSkill() {
        if (su.queryForObject("select count(*) from grading_skill_versions v join grading_skills s on s.id = v.grading_skill_id "
                + "where s.owner_org_id is null and s.question_type_code = 'IELTS_W_TASK2' and v.status = 'PUBLISHED'", Integer.class) > 0) {
            return;
        }
        UUID author = user("gq-author");
        UUID skill = su.queryForList("select id from grading_skills where owner_org_id is null and question_type_code = 'IELTS_W_TASK2'", UUID.class).stream().findFirst().orElseGet(() -> {
            UUID id = UUID.randomUUID();
            su.update("insert into grading_skills(id,owner_org_id,exam_code,question_type_code) values (?,null,'IELTS','IELTS_W_TASK2')", id);
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
        su.update("insert into responses(attempt_id,question_id,user_id,answer,answered_at,saved_at) values (?,?,?,?::jsonb,?,?)", attempt, fx.w().question(), learner,
                "\"" + essay + "\"", java.sql.Timestamp.from(at.minusSeconds(60)), java.sql.Timestamp.from(at.minusSeconds(60)));
        // the automatic scoring's grading record (it has nothing to score here); the announcement waits for the other skills' scores
        su.update("insert into attempt_gradings(attempt_id,user_id,owner_org_id,interrupted) values (?,?,?,false)", attempt, learner, org);
        return attempt;
    }

    private Attempt attemptOf(UUID attempt) {
        Map<String, Object> row = su.queryForMap("select user_id, owner_org_id from exam_attempts where id = ?", attempt);
        return queue.find(attempt, (UUID) row.get("user_id"), (UUID) row.get("owner_org_id")).orElseThrow();
    }

    /** The attempt goes to the teachers by the AI's emergency route (no policy row needed). @return the queue item */
    private UUID queued(UUID attempt) {
        assertTrue(queue.enqueue(attemptOf(attempt), GradingQueueService.Reason.POLICY), "queued by this call");
        return su.queryForObject("select id from grading_queue_items where attempt_id = ?", UUID.class, attempt);
    }

    private UUID teacher(String label, UUID org) {
        UUID t = user(label);
        grant(t, org, "IELTS", "TEACHER");
        return t;
    }

    private void aiScore(UUID attempt, UUID learner, UUID org, String skill, String kind, double band) {
        su.update("insert into scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max,scaled) values (?,?,?,?,?,'AI',?,null,?)", attempt, learner, org, skill, kind, band, band);
    }

    private List<String> ids(Res list) {
        List<String> out = new ArrayList<>();
        list.body().forEach(n -> out.add(n.path("id").asText()));
        return out;
    }

    private Res list(UUID teacher, UUID org) {
        return get("/api/teacher/queue", teacher, org);
    }

    private Res claim(UUID item, UUID teacher, UUID org) {
        return post("/api/teacher/queue/" + item + "/claim", null, teacher, org);
    }

    private int notifications(UUID user, String type) {
        return su.queryForObject("select count(*) from notifications where user_id = ? and event_type = ?", Integer.class, user, type);
    }

    /** One run of the job with the application clock at {@code at}; the clock goes back to now for the requests (a token is signed at the real time). */
    private void runJobAt(Instant at, String type) {
        clock.set(at);
        try {
            runJob(type);
        } finally {
            clock.set(Instant.now());
        }
    }

    private void runJob(String type) {
        String key = "it:" + UUID.randomUUID();
        new TransactionTemplate(tm).execute(s -> jobs.enqueueOnce(type, null, clock.instant(), key));
        long end = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < end) {
            String st = su.queryForObject("select status from jobs where type = ? and dedupe_key = ?", String.class, type, key);
            if ("SUCCEEDED".equals(st)) {
                return;
            }
            if ("FAILED".equals(st)) {
                throw new AssertionError(type + " failed: " + su.queryForObject("select last_error from jobs where type = ? and dedupe_key = ?", String.class, type, key));
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError(type + " did not finish");
    }

    // ---- the tests --------------------------------------------------------------------------------------------------------------------

    @Test
    void claimedHiddenFromOthers() throws Exception {
        Fx fx = test();
        UUID t1 = teacher("gq-t1", null);
        UUID t2 = teacher("gq-t2", null);
        UUID learner = user("gq-learner");
        Instant at = clock.instant();
        UUID attempt = submitted(fx, learner, null, at, "An essay.");
        UUID item = queued(attempt);
        assertEquals(1, notifications(t1, "GRADING_QUEUE_NEW"), "the teachers of the exam are told once");
        assertEquals(1, notifications(t2, "GRADING_QUEUE_NEW"));
        assertFalse(queue.enqueue(attemptOf(attempt), GradingQueueService.Reason.AI_FAILED), "one item per attempt");
        assertEquals(1, notifications(t1, "GRADING_QUEUE_NEW"));

        assertTrue(ids(list(t1, null)).contains(item.toString()));
        assertTrue(ids(list(t2, null)).contains(item.toString()));
        Res taken = claim(item, t1, null);
        assertEquals(200, taken.status(), taken.raw());
        assertEquals("CLAIMED", taken.body().path("state").asText());
        assertTrue(taken.body().path("mine").asBoolean());
        assertEquals(at.plus(Duration.ofHours(24)).toEpochMilli(), Instant.parse(taken.body().path("claimDueAt").asText()).toEpochMilli(), "e9x.grading.claim-timeout");

        assertFalse(ids(list(t2, null)).contains(item.toString()), "the other teacher no longer sees it");
        assertTrue(ids(list(t1, null)).contains(item.toString()), "the holder does");
        assertEquals(404, claim(item, t2, null).status(), "it cannot be taken again: it is the holder's and hidden");
        assertEquals(404, get("/api/teacher/queue/" + item, t2, null).status());
        assertEquals(404, post("/api/teacher/queue/" + item + "/release", null, t2, null).status());
        assertEquals(409, claim(item, t1, null).status(), "not even by the holder: it is no longer OPEN");

        // two teachers at the very same moment: the optimistic lock lets exactly one through
        UUID raceAttempt = submitted(fx, learner, null, at, "Another essay.");
        UUID race = queued(raceAttempt);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Res> a = pool.submit(() -> {
                go.await();
                return claim(race, t1, null);
            });
            Future<Res> b = pool.submit(() -> {
                go.await();
                return claim(race, t2, null);
            });
            go.countDown();
            List<Integer> statuses = List.of(a.get().status(), b.get().status());
            assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "exactly one claim wins: " + statuses);
            assertTrue(statuses.stream().filter(s -> s != 200).allMatch(s -> s == 409 || s == 404), statuses.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, su.queryForObject("select count(*) from grading_queue_items where id = ? and claimed_by is not null", Integer.class, race));
    }

    @Test
    void expiredClaimReturnsToQueue() {
        Fx fx = test();
        UUID t1 = teacher("gq-e1", null);
        UUID t2 = teacher("gq-e2", null);
        UUID learner = user("gq-e-learner");
        Instant at = clock.instant();
        UUID item = queued(submitted(fx, learner, null, at, "An essay."));
        assertEquals(200, claim(item, t1, null).status());

        // 30 minutes before the deadline: still the holder's, and the holder is warned, once
        runJobAt(at.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(30)), "release_expired_claim");
        assertEquals("CLAIMED", su.queryForObject("select state from grading_queue_items where id = ?", String.class, item));
        assertEquals(1, notifications(t1, "GRADING_CLAIM_DUE"), "the claim falls due within e9x.grading.claim-due-warning");
        runJobAt(at.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(31)), "release_expired_claim");
        assertEquals(1, notifications(t1, "GRADING_CLAIM_DUE"), "warned once per claim, however often the job runs");
        assertFalse(ids(list(t2, null)).contains(item.toString()));

        // past the deadline: back in the queue, nobody's
        runJobAt(at.plus(Duration.ofHours(24)).plusSeconds(1), "release_expired_claim");
        Map<String, Object> row = su.queryForMap("select state, claimed_by, claim_due_at from grading_queue_items where id = ?", item);
        assertEquals("OPEN", row.get("state"));
        assertNull(row.get("claimed_by"));
        assertNull(row.get("claim_due_at"));
        assertTrue(ids(list(t2, null)).contains(item.toString()), "the other teacher sees it again");
        assertEquals(409, get("/api/teacher/queue/" + item, t1, null).status(), "the first holder no longer has the work in hand");
        Res again = claim(item, t2, null);
        assertEquals(200, again.status(), again.raw());
        assertEquals(1, su.queryForObject("select count(*) from grading_queue_items where id = ? and claimed_by = ?", Integer.class, item, t2));
        runJobAt(Instant.now().plus(Duration.ofHours(1)), "release_expired_claim");
        assertEquals("CLAIMED", su.queryForObject("select state from grading_queue_items where id = ?", String.class, item), "a fresh claim is not released");
    }

    @Test
    void bothModeTeacherReplacesPreliminary() {
        publishedWritingSkill();
        Fx fx = test();
        // the policy of this test: BOTH
        su.update("insert into grading_policies(owner_org_id,exam_code,scope,test_id,mode) values (null,'IELTS','TEST',?,'BOTH')", fx.testId());
        UUID teacher = teacher("gq-b-teacher", null);
        UUID learner = user("gq-b-learner");
        Instant at = clock.instant();
        UUID attempt = submitted(fx, learner, null, at, "An essay.");
        // the AI's scores are there at once, as PRELIMINARY (mode BOTH), and the learner is told so
        aiScore(attempt, learner, null, "WRITING", "PRELIMINARY", 6.5);
        aiScore(attempt, learner, null, "SPEAKING", "PRELIMINARY", 6.0);
        announcer.announce(learner, null, attempt);
        assertEquals(1, scored.of(attempt).size());
        assertFalse(scored.of(attempt).get(0).official(), "the AI's score is preliminary");

        // not before the late-sync grace; then the attempt is in the teachers' queue by the policy
        clock.set(at.plus(GRACE).minusSeconds(1));
        assertEquals(GradingQueueService.Outcome.GRACE_NOT_OVER, queue.enqueueByPolicy(attempt, learner, null));
        clock.set(at.plus(GRACE).plusSeconds(1));
        assertEquals(GradingQueueService.Outcome.QUEUED, queue.enqueueByPolicy(attempt, learner, null));
        assertEquals(GradingQueueService.Outcome.ALREADY_QUEUED, queue.enqueueByPolicy(attempt, learner, null));
        clock.set(Instant.now()); // requests are signed at the real time
        UUID item = su.queryForObject("select id from grading_queue_items where attempt_id = ?", UUID.class, attempt);
        assertEquals("POLICY", su.queryForObject("select reason from grading_queue_items where id = ?", String.class, item));

        assertEquals(200, claim(item, teacher, null).status());
        Res detail = get("/api/teacher/queue/" + item, teacher, null);
        assertEquals(200, detail.status(), detail.raw());
        assertEquals("WRITING", detail.body().path("skills").get(0).path("skill").asText(), "in the order of the sections");
        assertEquals("An essay.", detail.body().path("skills").get(0).path("tasks").get(0).path("text").asText(), "the teacher reads the learner's work");
        assertEquals(6.5, detail.body().path("skills").get(0).path("aiBand").asDouble(), "and sees the AI's score");

        Res writing = review(item, teacher, "WRITING", Map.of("TA", 7.0, "CC", 7.0), "Well organised.");
        assertEquals(200, writing.status(), writing.raw());
        assertEquals(7.0, writing.body().path("band").asDouble());
        assertFalse(writing.body().path("done").asBoolean());
        assertEquals(1, scored.of(attempt).size(), "nothing is announced while a skill still waits for its teacher score");
        Res speaking = review(item, teacher, "SPEAKING", Map.of("FC", 6.0, "LR", 6.0), "Fluent enough.");
        assertEquals(200, speaking.status(), speaking.raw());
        assertTrue(speaking.body().path("done").asBoolean());

        // the teacher's score is OFFICIAL and replaces the AI's one; the AI's stays as the record of what the learner first saw
        Map<String, String> official = new java.util.TreeMap<>();
        su.queryForList("select skill, source, kind, scaled from scores where attempt_id = ? order by skill, kind", attempt)
                .forEach(r -> official.put(r.get("skill") + ":" + r.get("kind"), r.get("source") + "=" + ((BigDecimal) r.get("scaled")).doubleValue()));
        assertEquals("AI=6.5", official.get("WRITING:PRELIMINARY"));
        assertEquals("TEACHER=7.0", official.get("WRITING:OFFICIAL"));
        assertEquals("TEACHER=6.0", official.get("SPEAKING:OFFICIAL"));
        assertEquals("DONE", su.queryForObject("select state from grading_queue_items where id = ?", String.class, item));
        List<AttemptScored> events = scored.of(attempt);
        assertEquals(2, events.size(), "the preliminary announcement and the official one");
        assertTrue(events.get(1).official());
        assertEquals(7.0, events.get(1).skillScores().get("WRITING"), "the learner is shown the teacher's score");
        assertEquals(6.0, events.get(1).skillScores().get("SPEAKING"));
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> notifications(learner, "SCORE_OFFICIAL") == 1 && notifications(learner, "SCORE_PRELIMINARY") == 1);
        announcer.announce(learner, null, attempt);
        assertEquals(2, scored.of(attempt).size(), "announced once");
        assertEquals(409, review(item, teacher, "WRITING", Map.of("TA", 8.0, "CC", 8.0), "again").status(), "a graded item is final");
        clock.set(Instant.now());
    }

    @Test
    void deviationRecorded() {
        publishedWritingSkill();
        Fx fx = test();
        UUID teacher = teacher("gq-d-teacher", null);
        UUID learner = user("gq-d-learner");
        Instant at = clock.instant();
        UUID attempt = submitted(fx, learner, null, at, "An essay.");
        aiScore(attempt, learner, null, "WRITING", "PRELIMINARY", 6.5); // the AI scored the Writing only: no AI band for the Speaking
        UUID item = queued(attempt);
        assertEquals(200, claim(item, teacher, null).status());
        assertEquals(200, review(item, teacher, "WRITING", Map.of("TA", 8.0, "CC", 7.0), "Better than the AI thought.").status());
        assertEquals(200, review(item, teacher, "SPEAKING", Map.of("FC", 5.0), "Hesitant.").status());
        Map<String, Object> w = su.queryForMap("select band, ai_band, deviation, teacher_id, comment from teacher_reviews where attempt_id = ? and skill = 'WRITING'", attempt);
        assertEquals(7.5, ((BigDecimal) w.get("band")).doubleValue(), "mean 7.5");
        assertEquals(6.5, ((BigDecimal) w.get("ai_band")).doubleValue());
        assertEquals(1.0, ((BigDecimal) w.get("deviation")).doubleValue(), "teacher minus AI");
        assertEquals(teacher, w.get("teacher_id"));
        Map<String, Object> s = su.queryForMap("select band, ai_band, deviation from teacher_reviews where attempt_id = ? and skill = 'SPEAKING'", attempt);
        assertEquals(5.0, ((BigDecimal) s.get("band")).doubleValue());
        assertNull(s.get("ai_band"));
        assertNull(s.get("deviation"), "no AI score, no deviation");
    }

    @Test
    void centerQueueOnlyVisibleToCenterTeachers() {
        Fx fx = test();
        UUID center = org("CENTER");
        su.update("insert into org_grading_sides(org_id, side) values (?, 'CENTER')", center);
        UUID otherCenter = org("CENTER");
        UUID english9xCenter = org("CENTER"); // a centre that chose English9x: its learners' work goes to English9x's queue
        su.update("insert into org_grading_sides(org_id, side) values (?, 'ENGLISH9X')", english9xCenter);
        UUID centerTeacher = teacher("gq-c-teacher", center);
        UUID centerTeacher2 = teacher("gq-c-teacher2", center);
        UUID otherCenterTeacher = teacher("gq-o-teacher", otherCenter);
        UUID english9xTeacher = teacher("gq-e9-teacher", null);
        UUID learner = user("gq-c-learner");
        member(center, learner);
        UUID learner2 = user("gq-c2-learner");
        member(english9xCenter, learner2);
        Instant at = clock.instant();

        UUID centerItem = queued(submitted(fx, learner, center, at, "A centre essay."));
        UUID englishItem = queued(submitted(fx, learner2, english9xCenter, at, "An English9x essay."));
        assertEquals(center, su.queryForObject("select grading_org_id from grading_queue_items where id = ?", UUID.class, centerItem), "the centre chose its own teachers");
        assertNull(su.queryForObject("select grading_org_id from grading_queue_items where id = ?", UUID.class, englishItem), "the centre chose English9x");
        assertEquals(1, notifications(centerTeacher, "GRADING_QUEUE_NEW"), "the centre's teachers are told of the centre's item");
        assertEquals(0, notifications(otherCenterTeacher, "GRADING_QUEUE_NEW"), "not another centre's teachers");

        // the centre's teachers see the centre's item, in the centre's context
        assertEquals(List.of(centerItem.toString()), ids(list(centerTeacher, center)));
        assertEquals(List.of(centerItem.toString()), ids(list(centerTeacher2, center)));
        // English9x's teacher sees English9x's queue (and not the centre's); the centre's own items are invisible to every other teacher
        List<String> english9xSees = ids(list(english9xTeacher, null));
        assertTrue(english9xSees.contains(englishItem.toString()));
        assertFalse(english9xSees.contains(centerItem.toString()), "a centre's queue is not English9x's");
        assertEquals(404, claim(centerItem, english9xTeacher, null).status());
        assertEquals(404, get("/api/teacher/queue/" + centerItem, english9xTeacher, null).status());
        Res otherSees = list(otherCenterTeacher, otherCenter);
        assertEquals(200, otherSees.status());
        assertEquals(0, otherSees.body().size(), "another centre's teacher sees nothing");
        assertEquals(404, claim(centerItem, otherCenterTeacher, otherCenter).status());
        // the centre's teacher in the English9x context is no English9x teacher
        assertEquals(403, list(centerTeacher, null).status());
        assertEquals(404, claim(englishItem, centerTeacher, center).status(), "and the centre's teacher cannot take English9x's item from the centre's context");
        assertFalse(ids(list(centerTeacher, center)).contains(englishItem.toString()));

        // the administrator of the centre sees and hands over the centre's queue; an English9x teacher is not one of its teachers
        UUID admin = orgAdmin(center);
        Res adminList = get("/api/admin/queue", admin, center);
        assertEquals(200, adminList.status(), adminList.raw());
        assertEquals(List.of(centerItem.toString()), ids(adminList));
        assertEquals("CENTER", adminList.body().get(0).path("side").asText());
        assertEquals(422, post("/api/admin/queue/" + centerItem + "/reassign", Map.of("teacherId", english9xTeacher.toString()), admin, center).status());
        assertEquals(422, post("/api/admin/queue/" + centerItem + "/reassign", Map.of("teacherId", otherCenterTeacher.toString()), admin, center).status());
        Res handed = post("/api/admin/queue/" + centerItem + "/reassign", Map.of("teacherId", centerTeacher2.toString()), admin, center);
        assertEquals(200, handed.status(), handed.raw());
        assertEquals("CLAIMED", handed.body().path("state").asText());
        assertEquals(centerTeacher2.toString(), handed.body().path("claimedBy").asText());
        assertTrue(ids(list(centerTeacher2, center)).contains(centerItem.toString()));
        assertFalse(ids(list(centerTeacher, center)).contains(centerItem.toString()), "hidden from the other teacher once held");
        assertEquals(403, get("/api/admin/queue", centerTeacher, center).status(), "a teacher is not an administrator");
        // the SYS_ADMIN of English9x does not reach a centre's item, and the centre's admin none of English9x's
        UUID sys = sysAdmin();
        assertEquals(404, post("/api/admin/queue/" + centerItem + "/reassign", Map.of("teacherId", centerTeacher.toString()), sys, null).status());
        assertEquals(404, post("/api/admin/queue/" + englishItem + "/reassign", Map.of("teacherId", english9xTeacher.toString()), admin, center).status());
        Res english = post("/api/admin/queue/" + englishItem + "/reassign", Map.of("teacherId", english9xTeacher.toString()), sys, null);
        assertEquals(200, english.status(), english.raw());
        assertEquals(1, su.queryForObject("select count(*) from audit_log where action = 'grading-queue.reassign' and target_id = ?", Integer.class, englishItem.toString()));
    }

    @Test
    void detailShowsTheWorkAndTheReviewIsChecked() {
        publishedWritingSkill();
        Fx fx = test();
        UUID teacher = teacher("gq-v-teacher", null);
        UUID learner = user("gq-v-learner");
        Instant at = clock.instant();
        UUID attempt = submitted(fx, learner, null, at, "My essay about cities.");
        UUID item = queued(attempt);
        assertEquals(409, get("/api/teacher/queue/" + item, teacher, null).status(), "the work is not shown before the claim");
        assertEquals(200, claim(item, teacher, null).status());
        JsonNode skills = get("/api/teacher/queue/" + item, teacher, null).body().path("skills");
        assertEquals(List.of("SPEAKING", "WRITING"), java.util.stream.StreamSupport.stream(skills.spliterator(), false).map(s -> s.path("skill").asText()).sorted().toList());
        JsonNode writing = null;
        for (JsonNode s : skills) {
            if ("WRITING".equals(s.path("skill").asText())) {
                writing = s;
            }
        }
        assertEquals("My essay about cities.", writing.path("tasks").get(0).path("text").asText());
        assertEquals("Discuss both views.", writing.path("tasks").get(0).path("prompt").asText());
        assertEquals(List.of("CC", "TA"), java.util.stream.StreamSupport.stream(writing.path("criteria").spliterator(), false).map(c -> c.path("code").asText()).sorted().toList());
        assertFalse(get("/api/teacher/queue/" + item, teacher, null).raw().contains("cost"), "no cost or token counts");

        assertEquals(400, review(item, teacher, "WRITING", Map.of("TA", 10.0, "CC", 7.0), "").status(), "a score outside the criterion's range");
        assertEquals(400, review(item, teacher, "WRITING", Map.of("TA", 7.0, "XX", 7.0), "").status(), "a criterion the skill does not have");
        assertEquals(400, review(item, teacher, "WRITING", Map.of("TA", 7.0), "").status(), "every criterion is scored");
        assertEquals(400, review(item, teacher, "LISTENING", Map.of("TA", 7.0, "CC", 7.0), "").status(), "a skill of the attempt");
        assertEquals(400, review(item, teacher, null, Map.of("TA", 7.0, "CC", 7.0), "").status(), "two skills: name one");
        assertEquals(0, su.queryForObject("select count(*) from teacher_reviews where attempt_id = ?", Integer.class, attempt), "nothing was written");
        UUID other = teacher("gq-v-other", null);
        assertEquals(404, review(item, other, "WRITING", Map.of("TA", 7.0, "CC", 7.0), "").status(), "only the holder grades: it is hidden from the others");
        assertEquals(200, review(item, teacher, "WRITING", Map.of("TA", 7.0, "CC", 7.0), "ok").status());
        assertEquals(409, review(item, teacher, "WRITING", Map.of("TA", 7.0, "CC", 7.0), "twice").status(), "a skill is graded once");
        assertEquals("CLAIMED", su.queryForObject("select state from grading_queue_items where id = ?", String.class, item), "still held: the Speaking is not graded");
    }

    @Test
    void anOfficialScoreCommittedMeanwhileMakesTheTeacherInsertAConflictNotAnError() {
        Fx fx = test();
        UUID learner = user("gq-r-learner");
        UUID attempt = submitted(fx, learner, null, clock.instant(), "An essay.");
        su.update("insert into scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max,scaled) values (?,?,null,'WRITING','OFFICIAL','AI',6,null,6)", attempt, learner);
        Boolean written = graderDb.write(learner, null, j -> gradingStore.insertScore(j, attempt, learner, null, "WRITING", "OFFICIAL", new BigDecimal("7.0")));
        assertFalse(written, "0 rows: the service turns it into 409 already-official");
        assertEquals(1, su.queryForObject("select count(*) from scores where attempt_id = ? and skill = 'WRITING' and kind = 'OFFICIAL'", Integer.class, attempt));
    }

    private Res review(UUID item, UUID teacher, String skill, Map<String, Double> criteria, String comment) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (skill != null) {
            body.put("skill", skill);
        }
        body.put("criteria", criteria);
        body.put("comment", comment);
        return call(HttpMethod.POST, "/api/teacher/queue/" + item + "/review", body, teacher, null);
    }
}
