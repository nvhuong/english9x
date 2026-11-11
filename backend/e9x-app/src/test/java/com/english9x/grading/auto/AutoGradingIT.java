package com.english9x.grading.auto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.exam.api.events.AttemptSubmitted;
import com.english9x.exam.internal.ExamIT;
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
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Automatic scoring through the real stack: a TOEIC attempt made through the exam room's API (the real {@code AttemptSubmitted}, the real listener, the
 * job queue), and IELTS attempts written as rows (the IELTS test builder needs all four skills and a hundred questions; scoring reads rows, and the
 * event is published by the test the way the exam service publishes it). The background worker is off: the test runs the {@code grade_attempt} job
 * itself, with the clock moved to where it wants it. Conversion uses TEST-scope tables the test publishes (a table of the exam is another task's data
 * in the shared database).
 */
@Import(AutoGradingIT.Collect.class)
class AutoGradingIT extends ExamIT {

    @TestConfiguration
    static class Collect {
        @Bean
        Scored scoredEvents() {
            return new Scored();
        }
    }

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

    @Autowired Scored scored;
    @Autowired AutoGradeJob gradeJob;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;

    private static final Duration GRACE = Duration.ofMinutes(2); // e9x.exam.late-sync-grace

    // ---- TOEIC through the exam room ----------------------------------------------------------------------------------------------------

    private static UUID TOEIC_L;
    private static UUID TOEIC_R;

    /** The administrator's TEST-scope tables of the room's test: Listening and Reading each map 0 right to 5 and 1 right to 495. */
    private synchronized void toeicOverride(Room room) {
        if (TOEIC_L == null) {
            UUID testId = su.queryForObject("select test_id from test_versions where id = ?", UUID.class, room.version());
            TOEIC_L = table(testId, room.staff().org(), "TOEIC", "LISTENING", 1, 5, 495);
            TOEIC_R = table(testId, room.staff().org(), "TOEIC", "READING", 1, 5, 495);
        }
    }

    private record Sat(Room room, UUID learner, UUID attempt, Instant submittedAt) {}

    /** P1 answered right (key A), P5 answered wrong (key D), submitted by the learner. */
    private Sat satToeic() {
        Room room = room();
        toeicOverride(room);
        UUID learner = learner(room);
        Instant t0 = Instant.now();
        clock.set(t0);
        Res started = startOk(room, learner);
        UUID attempt = attemptId(started);
        clock.set(t0.plusSeconds(30));
        assertEquals(204, save(room, learner, attempt, firstQuestion(started), "A", t0.plusSeconds(20)).status());
        assertEquals(200, post("/api/exam/attempts/" + attempt + "/sections/P1/finish", null, learner, room.staff().org()).status());
        UUID reading = su.queryForObject("select question_id from attempt_questions where attempt_id = ? and section_code = 'P5'", UUID.class, attempt);
        clock.set(t0.plusSeconds(60));
        assertEquals(204, save(room, learner, attempt, reading, "C", t0.plusSeconds(50)).status());
        Instant submittedAt = t0.plusSeconds(90);
        clock.set(submittedAt);
        assertEquals(200, post("/api/exam/attempts/" + attempt + "/submit", null, learner, room.staff().org()).status());
        return new Sat(room, learner, attempt, submittedAt);
    }

    @Test
    void toeicScoresWithTestOverrideTable() {
        Sat s = satToeic();
        Job job = awaitJob(s.attempt());
        assertEquals(s.submittedAt().plus(GRACE).toEpochMilli(), jobRunAt(job).toEpochMilli(), "queued for the end of the late-sync grace, counted from the stored submission time");

        // not before the grace: an answer synced late is still welcome until then, so nothing is scored
        clock.set(s.submittedAt().plus(GRACE).minusSeconds(1));
        JobResult early = gradeJob.handle(job);
        assertFalse(early.isSuccess(), "the grace is not over: the job asks to be run again");
        assertEquals(0, count("scores", s.attempt()), "and writes nothing");
        assertEquals(0, count("attempt_gradings", s.attempt()));

        clock.set(s.submittedAt().plus(GRACE).plusSeconds(1));
        assertTrue(gradeJob.handle(job).isSuccess());

        Map<String, Map<String, Object>> rows = scoresOf(s.attempt());
        assertEquals(java.util.Set.of("LISTENING", "READING", "TOTAL"), rows.keySet());
        assertScore(rows.get("LISTENING"), 1, 1, 495, TOEIC_L);
        assertScore(rows.get("READING"), 0, 1, 5, TOEIC_R);
        UUID sum = su.queryForObject("select v.id from score_table_versions v join score_tables t on t.id = v.score_table_id "
                + "where t.exam_code = 'TOEIC' and t.skill = 'TOTAL' and t.scope = 'EXAM' and v.status = 'PUBLISHED'", UUID.class);
        assertScore(rows.get("TOTAL"), null, null, 500, sum);
        assertTrue(rows.values().stream().allMatch(r -> "OFFICIAL".equals(r.get("kind")) && "AUTO".equals(r.get("source"))));

        List<AttemptScored> announced = scored.of(s.attempt());
        assertEquals(1, announced.size());
        assertEquals(s.learner(), announced.get(0).userId());
        assertEquals(Map.of("LISTENING", 495.0, "READING", 5.0, "TOTAL", 500.0), announced.get(0).skillScores());
        assertTrue(announced.get(0).official());
    }

    @Test
    void interruptedAttemptScoredAndFlagged() {
        Room room = room();
        toeicOverride(room);
        UUID learner = learner(room);
        Instant t0 = Instant.now();
        clock.set(t0);
        UUID attempt = attemptId(startOk(room, learner));
        su.update("update exam_attempts set max_resumes = 1 where id = ?", attempt);
        assertEquals(200, post("/api/exam/attempts/" + attempt + "/resume", null, learner, room.staff().org()).status());
        Res over = post("/api/exam/attempts/" + attempt + "/resume", null, learner, room.staff().org());
        assertEquals(409, over.status(), over.raw());
        assertTrue(over.body().path("result").path("interrupted").asBoolean());

        Job job = awaitJob(attempt);
        clock.set(t0.plus(GRACE).plusSeconds(1));
        assertTrue(gradeJob.handle(job).isSuccess());

        Map<String, Map<String, Object>> rows = scoresOf(attempt);
        assertScore(rows.get("LISTENING"), 0, 1, 5, TOEIC_L);
        assertScore(rows.get("READING"), 0, 1, 5, TOEIC_R);
        assertScore(rows.get("TOTAL"), null, null, 10, rows.get("TOTAL").get("score_table_version_id"));
        assertEquals(true, su.queryForObject("select interrupted from attempt_gradings where attempt_id = ?", Boolean.class, attempt), "the warning label is kept with the grading");
        assertEquals(1, scored.of(attempt).size(), "an interrupted attempt is scored like any other");
        assertEquals(Map.of("LISTENING", 5.0, "READING", 5.0, "TOTAL", 10.0), scored.of(attempt).get(0).skillScores());
    }

    // ---- IELTS as rows ------------------------------------------------------------------------------------------------------------------

    private record Q(UUID question, UUID version) {}

    private record Fx(UUID testId, UUID version, Q l1, Q l2, Q r, Q w, Q s) {}

    private Q question(String type, String key, UUID author) {
        UUID q = UUID.randomUUID();
        UUID v = UUID.randomUUID();
        su.update("insert into questions(id,owner_org_id,exam_code,type_code,created_by) values (?,null,'IELTS',?,?)", q, type, author);
        su.update("insert into question_versions(id,question_id,owner_org_id,exam_code,type_code,version_no,status,content,answer_key,created_by) "
                + "values (?,?,null,'IELTS',?,1,'PUBLISHED','{\"stem\":\"s\"}'::jsonb,?::jsonb,?)", v, q, type, key, author);
        return new Q(q, v);
    }

    /** A PUBLISHED IELTS test of the variant, with the questions its attempts are scored against. Listening: 2 questions (partial scoring), Reading: 1. */
    private Fx ielts(String variant) {
        UUID author = user("ielts-author");
        UUID testId = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        su.update("insert into tests(id,owner_org_id,exam_code,variant,created_by) values (?,null,'IELTS',?,?)", testId, variant, author);
        su.update("insert into test_versions(id,test_id,owner_org_id,exam_code,variant,version_no,status,title,created_by) values (?,?,null,'IELTS',?,1,'PUBLISHED','IELTS fixture',?)",
                version, testId, variant, author);
        Fx fx = new Fx(testId, version,
                question("IELTS_L_SENTENCE_COMPLETION", "{\"slots\":[[\"Smith\"],[\"0123 456\",\"0123456\"]],\"maxWords\":2}", author),
                question("IELTS_L_MULTIPLE_CHOICE", "{\"slots\":[[\"A\",\"C\"]]}", author),
                question("IELTS_R_IDENTIFYING_INFORMATION", "{\"slots\":[[\"TRUE\"]]}", author),
                question("IELTS_W_TASK2", "{\"slots\":[[\"x\"]]}", author),
                question("IELTS_S_PART1", "{\"slots\":[[\"x\"]]}", author));
        // the structure of the test: one PUBLISHED group per section (the child tables of a published version are frozen by guards: the fixture lifts them)
        for (String t : new String[] {"test_parts", "test_part_groups", "question_group_items"}) {
            su.execute("alter table " + t + " disable trigger user");
        }
        String[] sections = {"LISTENING", "READING", "WRITING", "SPEAKING"};
        Q[][] members = {{fx.l1(), fx.l2()}, {fx.r()}, {fx.w()}, {fx.s()}};
        for (int i = 0; i < sections.length; i++) {
            UUID group = UUID.randomUUID();
            UUID groupVersion = UUID.randomUUID();
            su.update("insert into question_groups(id,owner_org_id,exam_code,created_by) values (?,null,'IELTS',?)", group, author);
            su.update("insert into question_group_versions(id,group_id,owner_org_id,exam_code,version_no,status,content,created_by) values (?,?,null,'IELTS',1,'PUBLISHED','{}'::jsonb,?)", groupVersion, group, author);
            for (int m = 0; m < members[i].length; m++) {
                su.update("insert into question_group_items(group_version_id,position,question_version_id,owner_org_id,exam_code) values (?,?,?,null,'IELTS')", groupVersion, m + 1, members[i][m].version());
            }
            su.update("insert into test_parts(test_version_id,position,section_code,duration_sec,owner_org_id,exam_code) values (?,?,?,600,null,'IELTS')", version, i + 1, sections[i]);
            su.update("insert into test_part_groups(test_version_id,part_position,position,group_version_id,owner_org_id,exam_code) values (?,?,1,?,null,'IELTS')", version, i + 1, groupVersion);
        }
        for (String t : new String[] {"test_parts", "test_part_groups", "question_group_items"}) {
            su.execute("alter table " + t + " enable trigger user");
        }
        return fx;
    }

    /** Listening: 0 right = 0, 1 = 4.0, 2 = 5.0, 3 = 6.0. Reading of the variant: 0 = 0, 1 = 3.5 (AC) or 4.5 (GT). */
    private UUID[] bandTables(Fx fx, String variant) {
        UUID listening = table(fx.testId(), null, "IELTS", "LISTENING", 1, 0, 4.0, 5.0, 6.0);
        UUID reading = table(fx.testId(), null, "IELTS", "READING_" + variant, 1, 0, "AC".equals(variant) ? 3.5 : 4.5, 6.0);
        return new UUID[] {listening, reading};
    }

    /**
     * A SUBMITTED attempt in the personal context. Listening: the sentence completion right (1 point), the multiple choice half right (0.5): raw 1.5 of 2.
     * Reading: right (1 of 1). With {@code writingSpeaking}, the attempt also has a Writing and a Speaking section whose questions no objective scorer scores.
     */
    private UUID submittedIelts(Fx fx, UUID learner, Instant submittedAt, boolean writingSpeaking) {
        UUID attempt = UUID.randomUUID();
        su.update("insert into exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,submitted_at,submit_reason,interrupted) "
                + "values (?,?,null,?,'SUBMITTED','IELTS',2,?,'LEARNER',false)", attempt, learner, fx.version(), java.sql.Timestamp.from(submittedAt));
        List<String[]> sections = new java.util.ArrayList<>(List.of(new String[] {"LISTENING"}, new String[] {"READING"}));
        if (writingSpeaking) {
            sections.add(new String[] {"WRITING"});
            sections.add(new String[] {"SPEAKING"});
        }
        for (int i = 0; i < sections.size(); i++) {
            su.update("insert into attempt_sections(attempt_id,position,user_id,section_code,skill,duration_sec,starts_at) values (?,?,?,?,?,600,?)", attempt, i, learner,
                    sections.get(i)[0], sections.get(i)[0], java.sql.Timestamp.from(submittedAt.minusSeconds(600)));
        }
        put(attempt, learner, fx.l1(), "LISTENING", "[\"smith\",\"0123456\"]", submittedAt);
        put(attempt, learner, fx.l2(), "LISTENING", "[\"A\"]", submittedAt);
        put(attempt, learner, fx.r(), "READING", "\"t\"", submittedAt);
        if (writingSpeaking) {
            put(attempt, learner, fx.w(), "WRITING", "\"An essay.\"", submittedAt);
            put(attempt, learner, fx.s(), "SPEAKING", null, submittedAt);
        }
        return attempt;
    }

    private void put(UUID attempt, UUID learner, Q q, String section, String answerJson, Instant at) {
        su.update("insert into attempt_questions(attempt_id,question_id,user_id,question_version_id,section_code) values (?,?,?,?,?)", attempt, q.question(), learner, q.version(), section);
        if (answerJson != null) {
            su.update("insert into responses(attempt_id,question_id,user_id,answer,answered_at,saved_at) values (?,?,?,?::jsonb,?,?)", attempt, q.question(), learner, answerJson,
                    java.sql.Timestamp.from(at.minusSeconds(60)), java.sql.Timestamp.from(at.minusSeconds(60)));
        }
    }

    private void publishSubmitted(UUID attempt, UUID learner, UUID testVersion, Optional<UUID> org, Instant at) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> events.publishEvent(new AttemptSubmitted(attempt, learner, testVersion, org, at, "LEARNER", false)));
    }

    @Test
    void ieltsListeningReadingBands() {
        Fx ac = ielts("AC");
        UUID[] tables = bandTables(ac, "AC");
        UUID learner = user("ielts-learner");
        Instant at = Instant.now();
        clock.set(at);
        UUID attempt = submittedIelts(ac, learner, at, true);
        publishSubmitted(attempt, learner, ac.version(), Optional.empty(), at);
        Job job = awaitJob(attempt);
        clock.set(at.plus(GRACE).plusSeconds(1));
        assertTrue(gradeJob.handle(job).isSuccess());

        Map<String, Map<String, Object>> rows = scoresOf(attempt);
        assertEquals(java.util.Set.of("LISTENING", "READING"), rows.keySet(), "Writing and Speaking are not scored here, and so there is no overall band");
        assertScore(rows.get("LISTENING"), 1.5, 2, 4.0, tables[0]); // 1.5 right: rounded DOWN to 1 for the table (1e-9 tolerance), the raw points are kept
        assertScore(rows.get("READING"), 1, 1, 3.5, tables[1]);  // Academic Reading, not General Training
        assertEquals(0, scored.of(attempt).size(), "AttemptScored waits for the skills the AI or a teacher scores");
        assertEquals(1, count("attempt_gradings", attempt));

        // General Training: its own Reading table
        Fx gt = ielts("GT");
        UUID[] gtTables = bandTables(gt, "GT");
        UUID gtAttempt = submittedIelts(gt, learner, at, false);
        publishSubmitted(gtAttempt, learner, gt.version(), Optional.empty(), at);
        Job gtJob = awaitJob(gtAttempt);
        assertTrue(gradeJob.handle(gtJob).isSuccess());
        Map<String, Map<String, Object>> gtRows = scoresOf(gtAttempt);
        assertEquals(java.util.Set.of("LISTENING", "READING"), gtRows.keySet(), "no overall band without Writing and Speaking");
        assertScore(gtRows.get("READING"), 1, 1, 4.5, gtTables[1]);
        assertEquals(1, scored.of(gtAttempt).size(), "every skill of the attempt has its score: announced");
        assertEquals(Map.of("LISTENING", 4.0, "READING", 4.5), scored.of(gtAttempt).get(0).skillScores());
        assertTrue(scored.of(gtAttempt).get(0).official());
    }

    @Test
    void scoreTableVersionRecorded() {
        Fx gt = ielts("GT");
        UUID[] v1 = bandTables(gt, "GT");
        UUID first = user("ielts-first");
        Instant at = Instant.now();
        clock.set(at);
        UUID a1 = submittedIelts(gt, first, at, false);
        publishSubmitted(a1, first, gt.version(), Optional.empty(), at);
        Job j1 = awaitJob(a1);
        clock.set(at.plus(GRACE).plusSeconds(1));
        assertTrue(gradeJob.handle(j1).isSuccess());
        assertScore(scoresOf(a1).get("LISTENING"), 1.5, 2, 4.0, v1[0]);

        // the administrator publishes version 2 of the Listening table (1 right is now 5.0): the finished result keeps the version it used
        UUID v2 = table(gt.testId(), null, "IELTS", "LISTENING", 2, 0, 5.0, 6.0, 7.0);
        assertTrue(gradeJob.handle(j1).isSuccess(), "a second run of the same job");
        assertScore(scoresOf(a1).get("LISTENING"), 1.5, 2, 4.0, v1[0]);
        assertEquals(1, scored.of(a1).size());

        // a later attempt of the same test is converted with version 2, and records it
        UUID second = user("ielts-second");
        UUID a2 = submittedIelts(gt, second, at, false);
        publishSubmitted(a2, second, gt.version(), Optional.empty(), at);
        Job j2 = awaitJob(a2);
        assertTrue(gradeJob.handle(j2).isSuccess());
        assertScore(scoresOf(a2).get("LISTENING"), 1.5, 2, 5.0, v2);
        assertEquals(Map.of("LISTENING", 5.0, "READING", 4.5), scored.of(a2).get(0).skillScores());
    }

    @Test
    void withoutAPublishedTableTheRawScoreIsKeptAndNothingIsGuessed() {
        Fx ac = ielts("AC"); // no table at all for this test; the exam keeps no default of IELTS Reading AC in this database either
        UUID learner = user("ielts-notable");
        Instant at = Instant.now();
        clock.set(at);
        UUID attempt = submittedIelts(ac, learner, at, false);
        // the exam default Listening may exist (other tests); the Reading AC default does not unless another test made it: only assert what is certain
        publishSubmitted(attempt, learner, ac.version(), Optional.empty(), at);
        Job job = awaitJob(attempt);
        clock.set(at.plus(GRACE).plusSeconds(1));
        assertTrue(gradeJob.handle(job).isSuccess(), "a missing table is not a failed job");
        Map<String, Map<String, Object>> rows = scoresOf(attempt);
        assertEquals(1.5, ((BigDecimal) rows.get("LISTENING").get("raw")).doubleValue());
        rows.values().stream().filter(r -> r.get("scaled") == null).forEach(r -> {
            assertNull(r.get("score_table_version_id"), "no table, no version");
        });
        if (rows.values().stream().anyMatch(r -> r.get("scaled") == null)) {
            assertEquals(0, scored.of(attempt).size(), "a skill with no converted score is not announced");
        }
    }

    // ---- membership comes from the test, not from what the learner can write ----------------------------------------------------------------

    @Test
    void extraQuestionsAddedAfterSubmitAreNotScored() {
        Sat s = satToeic(); // Listening right, Reading wrong: LISTENING 495, READING 5
        Fx fx = ielts("AC"); // a PUBLISHED question the learner knows the answer of, and that is not in their test
        // what a learner with raw SQL does after submitting (done here as the superuser: the DB guard that refuses it is asserted below)
        su.update("insert into attempt_questions(attempt_id,question_id,user_id,question_version_id,section_code) values (?,?,?,?,'P5')", s.attempt(), fx.r().question(), s.learner(), fx.r().version());
        su.update("insert into responses(attempt_id,question_id,user_id,answer,answered_at,saved_at) values (?,?,?,'\"t\"',now(),now())", s.attempt(), fx.r().question(), s.learner());
        Job job = awaitJob(s.attempt());
        clock.set(s.submittedAt().plus(GRACE).plusSeconds(1));
        assertTrue(gradeJob.handle(job).isSuccess());
        Map<String, Map<String, Object>> rows = scoresOf(s.attempt());
        assertScore(rows.get("READING"), 0, 1, 5, TOEIC_R); // the same as without the extra row: raw 0 of 1
        assertScore(rows.get("LISTENING"), 1, 1, 495, TOEIC_L);
    }

    private void asLearner(UUID learner, String sql, boolean expectRefused) throws Exception {
        try (java.sql.Connection c = com.english9x.support.TestPostgres.app(DB).getConnection()) {
            c.setAutoCommit(false);
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("select set_config('app.user_id','" + learner + "',true), set_config('app.org_id','',true), set_config('app.e9x_staff','',true), set_config('app.org_admin','',true)");
                try {
                    st.executeUpdate(sql);
                    c.commit();
                    assertFalse(expectRefused, "the database must refuse: " + sql);
                } catch (java.sql.SQLException e) {
                    assertTrue(expectRefused, "the database must accept: " + sql + " " + e.getMessage());
                    assertEquals("42501", e.getSQLState());
                }
            }
        }
    }

    @Test
    void theDatabaseFreezesTheQuestionsAndTheAnswerWindowOfASubmittedAttempt() throws Exception {
        Fx fx = ielts("AC");
        UUID learner = user("ielts-guard");
        // submitted an hour ago: its last answer window closed long ago, and no grace reaches that far
        UUID old = submittedIelts(fx, learner, Instant.now().minus(Duration.ofHours(1)), true);
        Q extra = ielts("GT").r(); // a published question that is not in the attempt's test
        asLearner(learner, "insert into attempt_questions(attempt_id,question_id,user_id,question_version_id,section_code) values ('" + old + "','" + extra.question() + "','" + learner + "','"
                + extra.version() + "','SPEAKING')", true);
        String late = "insert into responses(attempt_id,question_id,user_id,answer,answered_at,saved_at) values ('" + old + "','" + fx.s().question() + "','" + learner + "','\"x\"',now() - interval '61 minutes',now())";
        asLearner(learner, late, true);
        asLearner(learner, "update responses set answer = '\"changed\"' where attempt_id = '" + old + "'", true);
        // submitted a minute ago: an answer written inside the section's window may still be synced during the grace
        UUID fresh = submittedIelts(fx, learner, Instant.now().minus(Duration.ofMinutes(1)), true);
        asLearner(learner, "insert into responses(attempt_id,question_id,user_id,answer,answered_at,saved_at) values ('" + fresh + "','" + fx.s().question() + "','" + learner
                + "','\"x\"',now() - interval '120 seconds',now())", false);
        // but not one claimed to be written after the window closed
        UUID another = submittedIelts(fx, user("ielts-guard2"), Instant.now().minus(Duration.ofMinutes(1)), true);
        asLearner(su.queryForObject("select user_id from exam_attempts where id = ?", UUID.class, another),
                "insert into responses(attempt_id,question_id,user_id,answer,answered_at,saved_at) values ('" + another + "','" + fx.s().question() + "','"
                        + su.queryForObject("select user_id from exam_attempts where id = ?", UUID.class, another) + "','\"x\"',now(),now())", true);
    }

    // ---- the event and the job are hints --------------------------------------------------------------------------------------------------

    @Test
    void idempotentOnDuplicateEvent() {
        Fx gt = ielts("GT");
        UUID[] tables = bandTables(gt, "GT");
        UUID learner = user("ielts-dup");
        Instant at = Instant.now();
        clock.set(at);
        UUID attempt = submittedIelts(gt, learner, at, false);
        publishSubmitted(attempt, learner, gt.version(), Optional.empty(), at);
        publishSubmitted(attempt, learner, gt.version(), Optional.empty(), at); // delivered twice
        awaitListeners();
        assertEquals(1, su.queryForObject("select count(*) from jobs where type = 'grade_attempt' and payload->>'attemptId' = ?", Integer.class, attempt.toString()), "one job per attempt");
        Job job = awaitJob(attempt);

        clock.set(at.plus(GRACE).plusSeconds(1));
        assertTrue(gradeJob.handle(job).isSuccess());
        assertTrue(gradeJob.handle(job).isSuccess(), "the job is delivered at least once");
        publishSubmitted(attempt, learner, gt.version(), Optional.empty(), at); // and the event again after it was scored
        awaitListeners();
        assertTrue(gradeJob.handle(awaitJob(attempt)).isSuccess());

        assertEquals(2, count("scores", attempt), "LISTENING and READING once, however often it was delivered");
        assertEquals(1, count("attempt_gradings", attempt));
        assertEquals(1, scored.of(attempt).size(), "AttemptScored is published once");
        assertScore(scoresOf(attempt).get("READING"), 1, 1, 4.5, tables[1]);
    }

    @Test
    void anEventIsOnlyAHint() {
        Fx gt = ielts("GT");
        bandTables(gt, "GT");
        UUID learner = user("ielts-hint");
        Instant at = Instant.now();
        clock.set(at);
        UUID attempt = submittedIelts(gt, learner, at, false);
        publishSubmitted(UUID.randomUUID(), learner, gt.version(), Optional.empty(), at);               // no such attempt
        publishSubmitted(attempt, user("somebody-else"), gt.version(), Optional.empty(), at);            // the attempt of somebody else
        publishSubmitted(attempt, learner, UUID.randomUUID(), Optional.empty(), at);                     // another test version
        publishSubmitted(attempt, learner, gt.version(), Optional.of(UUID.randomUUID()), at);            // another organisation
        awaitListeners();
        assertEquals(0, su.queryForObject("select count(*) from jobs where type = 'grade_attempt' and payload->>'attemptId' = ?", Integer.class, attempt.toString()),
                "nothing is queued for an event the attempt does not bear out");
        publishSubmitted(attempt, learner, gt.version(), Optional.empty(), at);                          // the true one
        assertEquals(attempt.toString(), awaitJob(attempt).payload().path("attemptId").asText());
    }

    @Test
    void aForgedJobScoresNothing() {
        Fx gt = ielts("GT");
        bandTables(gt, "GT");
        UUID learner = user("ielts-forged");
        Instant at = Instant.now();
        UUID attempt = submittedIelts(gt, learner, at, false);
        clock.set(at.plus(GRACE).plusSeconds(10));
        for (String[] ids : new String[][] {{attempt.toString(), user("thief").toString(), ""}, {attempt.toString(), learner.toString(), UUID.randomUUID().toString()},
                {UUID.randomUUID().toString(), learner.toString(), ""}}) {
            ObjectNode payload = JSON.createObjectNode().put("attemptId", ids[0]).put("userId", ids[1]);
            if (!ids[2].isEmpty()) {
                payload.put("orgId", ids[2]);
            }
            assertTrue(gradeJob.handle(new Job(UUID.randomUUID(), "grade_attempt", payload, 1, Instant.now())).isSuccess(), "ends as a no-op, not a retry loop");
        }
        assertEquals(0, count("scores", attempt));
        assertEquals(0, count("attempt_gradings", attempt));
        // an attempt still IN_PROGRESS is never scored, whatever the payload says
        UUID running = UUID.randomUUID();
        su.update("insert into exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,deadline_at) values (?,?,null,?,'IN_PROGRESS','IELTS',2,now())", running,
                learner, gt.version());
        assertTrue(gradeJob.handle(new Job(UUID.randomUUID(), "grade_attempt", JSON.createObjectNode().put("attemptId", running.toString()).put("userId", learner.toString()), 1,
                Instant.now())).isSuccess());
        assertEquals(0, count("attempt_gradings", running));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------------------

    private UUID table(UUID testId, UUID owner, String exam, String skill, int versionNo, double... scaledByRaw) {
        UUID header = su.queryForList("select id from score_tables where scope = 'TEST' and test_id = ? and skill = ?", UUID.class, testId, skill).stream().findFirst().orElseGet(() -> {
            UUID id = UUID.randomUUID();
            su.update("insert into score_tables(id,owner_org_id,exam_code,skill,scope,test_id) values (?,?,?,?,'TEST',?)", id, owner, exam, skill, testId);
            return id;
        });
        StringBuilder entries = new StringBuilder("[");
        for (int raw = 0; raw < scaledByRaw.length; raw++) {
            entries.append(raw == 0 ? "" : ",").append("{\"raw\":").append(raw).append(",\"scaled\":").append(scaledByRaw[raw]).append('}');
        }
        UUID version = UUID.randomUUID();
        su.update("insert into score_table_versions(id,score_table_id,owner_org_id,exam_code,version_no,status,entries) values (?,?,?,?,?,'PUBLISHED',?::jsonb)", version, header, owner, exam,
                versionNo, entries.append(']').toString());
        return version;
    }

    private Job awaitJob(UUID attempt) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(
                () -> su.queryForObject("select count(*) from jobs where type = 'grade_attempt' and payload->>'attemptId' = ?", Integer.class, attempt.toString()) > 0);
        Map<String, Object> row = su.queryForMap("select id, payload::text as p from jobs where type = 'grade_attempt' and payload->>'attemptId' = ? order by run_at limit 1", attempt.toString());
        try {
            return new Job((UUID) row.get("id"), "grade_attempt", JSON.readTree((String) row.get("p")), 1, Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Instant jobRunAt(Job job) {
        return su.queryForObject("select run_at from jobs where id = ?", java.sql.Timestamp.class, job.id()).toInstant();
    }

    /** Every listener of AttemptSubmitted has finished (a finished publication is deleted, completion-mode=delete). */
    private void awaitListeners() {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> su.queryForObject(
                "select count(*) from event_publication where listener_id like '%AttemptSubmittedListener%' and completion_date is null", Integer.class) == 0);
    }

    private int count(String table, UUID attempt) {
        return su.queryForObject("select count(*) from " + table + " where attempt_id = ?", Integer.class, attempt);
    }

    private Map<String, Map<String, Object>> scoresOf(UUID attempt) {
        return su.queryForList("select skill, kind, source, raw, raw_max, scaled, score_table_version_id from scores where attempt_id = ? order by skill", attempt).stream()
                .collect(Collectors.toMap(r -> (String) r.get("skill"), r -> r, (a, b) -> a, java.util.LinkedHashMap::new));
    }

    private static void assertScore(Map<String, Object> row, Number raw, Number max, double scaled, Object version) {
        assertEquals(raw == null ? null : raw.doubleValue(), row.get("raw") == null ? null : ((BigDecimal) row.get("raw")).doubleValue(), "raw of " + row.get("skill"));
        assertEquals(max == null ? null : max.doubleValue(), row.get("raw_max") == null ? null : ((BigDecimal) row.get("raw_max")).doubleValue(), "max of " + row.get("skill"));
        assertEquals(scaled, ((BigDecimal) row.get("scaled")).doubleValue(), "scaled of " + row.get("skill"));
        assertEquals(version, row.get("score_table_version_id"), "table version of " + row.get("skill"));
    }
}
