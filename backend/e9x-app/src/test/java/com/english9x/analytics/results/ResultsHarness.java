package com.english9x.analytics.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.exam.internal.ExamIT;
import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * What the three results tests share: a TOEIC attempt sat through the exam room's API (P1 and P5, one question each; keys A and D), scored by the real {@code grade_attempt} job
 * (run by hand with the clock moved past the late-sync grace; the worker is off), and method lessons written as rows.
 */
abstract class ResultsHarness extends ExamIT {

    protected static final Duration GRACE = Duration.ofMinutes(2); // e9x.exam.late-sync-grace

    @Autowired ApplicationContext context;

    protected record Sat(Room room, UUID learner, UUID attempt, Instant submittedAt) {}

    /** Starts an attempt and answers P1 and P5 (null = leave blank); not submitted. */
    protected Sat start(Room room, UUID learner, String p1, String p5) {
        Instant t0 = Instant.now();
        clock.set(t0);
        Res started = startOk(room, learner);
        UUID attempt = attemptId(started);
        clock.set(t0.plusSeconds(30));
        if (p1 != null) {
            assertEquals(204, save(room, learner, attempt, firstQuestion(started), p1, t0.plusSeconds(20)).status());
        }
        assertEquals(200, post("/api/exam/attempts/" + attempt + "/sections/P1/finish", null, learner, room.staff().org()).status());
        if (p5 != null) {
            UUID reading = su.queryForObject("select question_id from attempt_questions where attempt_id = ? and section_code = 'P5'", UUID.class, attempt);
            clock.set(t0.plusSeconds(60));
            assertEquals(204, save(room, learner, attempt, reading, p5, t0.plusSeconds(50)).status());
        }
        return new Sat(room, learner, attempt, null);
    }

    /** Submits it (inside the grace: not scored yet). */
    protected Sat submit(Sat s) {
        Instant at = clock.instant().plusSeconds(30);
        clock.set(at);
        assertEquals(200, post("/api/exam/attempts/" + s.attempt() + "/submit", null, s.learner(), s.room().staff().org()).status());
        return new Sat(s.room(), s.learner(), s.attempt(), at);
    }

    /** Runs the automatic scoring of the submitted attempt, once the grace is over. */
    protected void grade(Sat s) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(
                () -> su.queryForObject("select count(*) from jobs where type = 'grade_attempt' and payload->>'attemptId' = ?", Integer.class, s.attempt().toString()) > 0);
        Map<String, Object> row = su.queryForMap("select id, payload::text as p from jobs where type = 'grade_attempt' and payload->>'attemptId' = ? order by run_at limit 1", s.attempt().toString());
        Job job;
        try {
            job = new Job((UUID) row.get("id"), "grade_attempt", JSON.readTree((String) row.get("p")), 1, Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        clock.set(s.submittedAt().plus(GRACE).plusSeconds(1));
        JobExecutor executor = context.getBeansOfType(JobExecutor.class).values().stream().filter(e -> e.getClass().getSimpleName().equals("AutoGradeJob")).findFirst().orElseThrow();
        try {
            assertTrue(executor.handle(job).isSuccess());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Sat sat(Room room, UUID learner, String p1, String p5) {
        Sat s = submit(start(room, learner, p1, p5));
        grade(s);
        return s;
    }

    /** A published course with ONE published method lesson tagged with the question type. @return the course id; the learner is entitled when {@code validFor} is not null. */
    protected UUID methodLesson(String typeCode, String title, UUID entitledLearner, Duration validFromAgo, Duration validFor) {
        UUID author = user("lesson-author");
        UUID course = UUID.randomUUID();
        UUID courseVersion = UUID.randomUUID();
        UUID lesson = UUID.randomUUID();
        UUID lessonVersion = UUID.randomUUID();
        su.update("insert into courses(id,owner_org_id,exam_code,code,title,status) values (?,null,'TOEIC',?,?, 'PUBLISHED')", course,
                UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(), "Course of " + title);
        su.update("insert into course_versions(id,course_id,owner_org_id,exam_code,version_no,status,title,unlock_rule,created_by) values (?,?,null,'TOEIC',1,'PUBLISHED',?,'FREE',?)",
                courseVersion, course, "Course of " + title, author);
        su.update("insert into strategy_lessons(id,owner_org_id,exam_code,created_by) values (?,null,'TOEIC',?)", lesson, author);
        su.update("insert into strategy_lesson_versions(id,lesson_id,owner_org_id,exam_code,version_no,status,title,blocks,created_by) values (?,?,null,'TOEIC',1,'PUBLISHED',?,'[]'::jsonb,?)",
                lessonVersion, lesson, title, author);
        for (String t : new String[] {"strategy_lesson_types", "course_chapters", "course_lessons"}) {
            su.execute("alter table " + t + " disable trigger user");
        }
        su.update("insert into strategy_lesson_types(version_id,type_code,owner_org_id,exam_code) values (?,?,null,'TOEIC')", lessonVersion, typeCode);
        su.update("insert into course_chapters(course_version_id,position,title,owner_org_id,exam_code) values (?,1,'Chapter',null,'TOEIC')", courseVersion);
        su.update("insert into course_lessons(course_version_id,chapter_position,position,kind,ref_id,title,owner_org_id,exam_code) values (?,1,1,'LESSON',?,?,null,'TOEIC')",
                courseVersion, lessonVersion, title);
        for (String t : new String[] {"strategy_lesson_types", "course_chapters", "course_lessons"}) {
            su.execute("alter table " + t + " enable trigger user");
        }
        if (entitledLearner != null) {
            Instant now = clock.instant();
            su.update("insert into entitlements(user_id,course_id,valid_from,valid_until,source_order_id) values (?,?,?,?,gen_random_uuid())", entitledLearner, course,
                    Timestamp.from(now.minus(validFromAgo)), Timestamp.from(now.minus(validFromAgo).plus(validFor)));
        }
        return course;
    }
}
