package com.english9x.exam.internal;

import com.english9x.content.test.TestIT;
import com.english9x.exam.api.events.AttemptSubmitted;
import com.english9x.platform.jobs.api.Job;
import com.english9x.support.MutableClock;
import com.english9x.support.TestMinio;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Harness of the exam tests: a TOEIC organisation with a PUBLISHED test (P1 listening 600 s, P5 reading 1200 s), learners who are members of it and hold an
 * entitlement to a TOEIC course, a clock the tests move by hand, and a collector of {@link AttemptSubmitted} events (after commit only). The background worker is
 * OFF: the tests call the auto-submit handler themselves, so a race is exactly the two calls they make.
 */
@Import(ExamIT.Config.class)
@TestPropertySource(properties = "app.worker.enabled=false")
public abstract class ExamIT extends TestIT {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        MutableClock examClock() {
            return new MutableClock(Instant.now());
        }

        @Bean
        Submitted submittedEvents() {
            return new Submitted();
        }
    }

    static class Submitted {
        final List<AttemptSubmitted> seen = new CopyOnWriteArrayList<>();

        @TransactionalEventListener
        void on(AttemptSubmitted e) {
            seen.add(e);
        }

        List<AttemptSubmitted> all() {
            return List.copyOf(seen);
        }

        long count(UUID attemptId) {
            return seen.stream().filter(e -> e.attemptId().equals(attemptId)).count();
        }
    }

    @DynamicPropertySource
    static void minio(DynamicPropertyRegistry registry) {
        TestMinio.configure(registry);
    }

    @Autowired protected MutableClock clock;
    @Autowired protected Submitted submitted;
    @Autowired protected ExamService service;
    @Autowired protected AutoSubmitJob autoSubmit;

    private static Staff STAFF;
    private static UUID VERSION;

    protected record Room(Staff staff, UUID version) {}

    protected synchronized Room room() {
        if (STAFF == null) {
            Staff s = staff("TOEIC");
            Built b = buildToeicTest(s, "Mock test");
            VERSION = approveTest(b.test(), s);
            STAFF = s;
        }
        return new Room(STAFF, VERSION);
    }

    /** A member of the room's organisation with a plan for a TOEIC course (valid around the clock's now). */
    protected UUID learner(Room room) {
        UUID u = user("learner");
        member(room.staff().org(), u);
        UUID course = UUID.randomUUID();
        su.update("insert into courses(id,owner_org_id,exam_code,code,title,status) values (?,null,'TOEIC',?,'Course','PUBLISHED')", course,
                UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
        Instant now = clock.instant();
        su.update("insert into entitlements(user_id,course_id,valid_from,valid_until,source_order_id) values (?,?,?,?,gen_random_uuid())", u, course,
                Timestamp.from(now.minus(Duration.ofDays(1))), Timestamp.from(now.plus(Duration.ofDays(60))));
        return u;
    }

    protected Res start(Room room, UUID learner) {
        return post("/api/exam/attempts", Map.of("testVersionId", room.version().toString()), learner, room.staff().org());
    }

    protected Res startOk(Room room, UUID learner) {
        Res r = start(room, learner);
        if (r.status() != 201) {
            throw new AssertionError(r.status() + " " + r.raw());
        }
        return r;
    }

    protected static UUID attemptId(Res r) {
        return UUID.fromString(r.body().path("attemptId").asText());
    }

    protected static UUID firstQuestion(Res view) {
        return UUID.fromString(view.body().path("currentSection").path("groups").get(0).path("questions").get(0).path("questionId").asText());
    }

    protected Res save(Room room, UUID learner, UUID attempt, UUID question, Object answer, Instant answeredAt) {
        return call(org.springframework.http.HttpMethod.PUT, "/api/exam/attempts/" + attempt + "/responses/" + question,
                Map.of("answer", answer, "answeredAt", answeredAt.toString()), learner, room.staff().org());
    }

    protected Job jobOf(UUID attempt) {
        Map<String, Object> row = su.queryForMap("select id, payload::text as p, run_at from jobs where type = 'auto_submit_attempt' and payload->>'attemptId' = ? order by run_at limit 1",
                attempt.toString());
        try {
            JsonNode payload = JSON.readTree((String) row.get("p"));
            return new Job((UUID) row.get("id"), "auto_submit_attempt", payload, 1, Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected String statusOf(UUID attempt) {
        return su.queryForObject("select status from exam_attempts where id = ?", String.class, attempt);
    }
}
