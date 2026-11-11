package com.english9x.classroom.assignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.exam.api.events.AttemptSubmitted;
import com.english9x.org.OrgIT;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** F46: an exam given to a class with a due date; handed in on time, LATE, or MISSING. */
class AssignmentIT extends OrgIT {

    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;

    private record Fx(UUID org, UUID admin, UUID teacher, UUID learner, UUID classmate, UUID cls, UUID testVersion) {}

    private Fx fixture() {
        UUID org = org("CENTER");
        UUID admin = orgAdmin(org);
        UUID teacher = user("teacher");
        grant(teacher, org, "IELTS", "TEACHER");
        UUID learner = user("learner");
        grant(learner, org, null, "LEARNER");
        UUID classmate = user("classmate");
        grant(classmate, org, null, "LEARNER");
        UUID cls = UUID.fromString(post("/api/orgs/" + org + "/classes", Map.of("name", "Lop " + UUID.randomUUID().toString().substring(0, 4), "teacherId", teacher), admin, org)
                .body().path("id").asText());
        assertEquals(200, post("/api/orgs/" + org + "/classes/" + cls + "/members", Map.of("userIds", List.of(learner, classmate)), teacher, org).status());
        UUID test = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        su.update("insert into tests(id,owner_org_id,exam_code,variant,created_by) values (?,null,'IELTS','AC',?)", test, admin);
        su.update("insert into test_versions(id,test_id,owner_org_id,exam_code,variant,version_no,status,title,created_by) values (?,?,null,'IELTS','AC',1,'PUBLISHED','Mock test 1',?)",
                version, test, admin);
        return new Fx(org, admin, teacher, learner, classmate, cls, version);
    }

    private UUID give(Fx f, Instant due) {
        Res r = post("/api/orgs/" + f.org + "/classes/" + f.cls + "/assignments", Map.of("testVersionId", f.testVersion, "dueAt", due.toString()), f.teacher, f.org);
        assertEquals(201, r.status(), r.raw());
        return UUID.fromString(r.body().path("id").asText());
    }

    private UUID submitted(UUID user, UUID org, UUID testVersion, Instant at) {
        UUID attempt = UUID.randomUUID();
        su.update("insert into exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,submitted_at,submit_reason,interrupted) "
                + "values (?,?,?,?,'SUBMITTED','IELTS',2,?,'LEARNER',false)", attempt, user, org, testVersion, Timestamp.from(at));
        publish(attempt, user, org, testVersion, at);
        return attempt;
    }

    private void publish(UUID attempt, UUID user, UUID org, UUID testVersion, Instant at) {
        new TransactionTemplate(transactions).executeWithoutResult(s ->
                events.publishEvent(new AttemptSubmitted(attempt, user, testVersion, Optional.of(org), at, "LEARNER", false)));
    }

    private void due(UUID assignment, Instant at) {
        su.update("update assignments set due_at = ? where id = ?", Timestamp.from(at), assignment);
    }

    private String statusFor(Fx f, UUID assignment, UUID user) {
        Res r = get("/api/orgs/" + f.org + "/assignments/" + assignment, f.teacher, f.org);
        assertEquals(200, r.status(), r.raw());
        for (var row : r.body().path("rows")) {
            if (row.path("userId").asText().equals(user.toString())) {
                return row.path("status").asText();
            }
        }
        throw new AssertionError("not in the report: " + r.raw());
    }

    private String mine(Fx f, UUID assignment, UUID user) {
        Res r = get("/api/me/assignments", user, f.org);
        assertEquals(200, r.status(), r.raw());
        for (var a : r.body()) {
            if (a.path("id").asText().equals(assignment.toString())) {
                return a.path("status").asText();
            }
        }
        throw new AssertionError("not in my assignments: " + r.raw());
    }

    @Test
    void attemptSubmittedEventMarksSubmission() {
        Fx f = fixture();
        UUID a = give(f, Instant.now().plus(2, ChronoUnit.DAYS));
        assertEquals("PENDING", mine(f, a, f.learner));
        assertEquals("PENDING", statusFor(f, a, f.learner));

        // the learner is told (once) that there is work for them; so is the classmate
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> su.queryForObject(
                "select count(*) from notifications where event_type = 'ASSIGNMENT_NEW' and dedupe_key = ?", Integer.class, "assignment-new:" + a) == 2);

        // a forged event naming an attempt that does not exist marks nothing
        publish(UUID.randomUUID(), f.learner, f.org, f.testVersion, Instant.now());
        // the real attempt marks the learner, not the classmate
        UUID attempt = submitted(f.learner, f.org, f.testVersion, Instant.now());
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> su.queryForObject(
                "select count(*) from assignment_submissions where assignment_id = ? and user_id = ? and status = 'SUBMITTED' and attempt_id = ?", Integer.class, a, f.learner, attempt) == 1);
        assertEquals("SUBMITTED", mine(f, a, f.learner));
        assertEquals("SUBMITTED", statusFor(f, a, f.learner));
        assertEquals("PENDING", statusFor(f, a, f.classmate));
        assertEquals(0, su.queryForObject("select count(*) from assignment_submissions where assignment_id = ? and user_id = ?", Integer.class, a, f.classmate));

        // the event delivered again, or a second attempt, changes nothing (the first counts)
        publish(attempt, f.learner, f.org, f.testVersion, Instant.now());
        submitted(f.learner, f.org, f.testVersion, Instant.now());
        Awaitility.await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(30)).until(() -> su.queryForObject(
                "select count(*) from event_publication where completion_date is null", Integer.class) == 0);
        assertEquals(1, su.queryForObject("select count(*) from assignment_submissions where assignment_id = ?", Integer.class, a));
        assertEquals(1, su.queryForObject("select count(*) from notifications where event_type = 'ASSIGNMENT_NEW' and user_id = ?", Integer.class, f.learner));

        // an attempt made outside the organisation (personal context) is nobody's assignment
        UUID personal = UUID.randomUUID();
        su.update("insert into exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,submitted_at,submit_reason,interrupted) "
                + "values (?,?,null,?,'SUBMITTED','IELTS',2,now(),'LEARNER',false)", personal, f.classmate, f.testVersion);
        new TransactionTemplate(transactions).executeWithoutResult(s -> events.publishEvent(
                new AttemptSubmitted(personal, f.classmate, f.testVersion, Optional.empty(), Instant.now(), "LEARNER", false)));
        Awaitility.await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(30)).until(() -> su.queryForObject(
                "select count(*) from event_publication where completion_date is null", Integer.class) == 0);
        assertEquals("PENDING", statusFor(f, a, f.classmate));
    }

    @Test
    void lateSubmissionFlagged() {
        Fx f = fixture();
        UUID a = give(f, Instant.now().plus(1, ChronoUnit.HOURS));
        due(a, Instant.now().minus(1, ChronoUnit.HOURS));   // the due date passes before the learner hands in
        UUID attempt = submitted(f.learner, f.org, f.testVersion, Instant.now());
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> su.queryForObject(
                "select count(*) from assignment_submissions where assignment_id = ? and user_id = ? and status = 'LATE' and attempt_id = ?", Integer.class, a, f.learner, attempt) == 1);
        assertEquals("LATE", mine(f, a, f.learner));
        assertEquals("LATE", statusFor(f, a, f.learner));
        assertEquals("MISSING", statusFor(f, a, f.classmate), "the classmate has handed in nothing");

        Res list = get("/api/orgs/" + f.org + "/classes/" + f.cls + "/assignments", f.teacher, f.org);
        assertEquals(200, list.status(), list.raw());
        var row = list.body().get(0);
        assertEquals(2, row.path("total").asInt());
        assertEquals(1, row.path("late").asInt());
        assertEquals(1, row.path("missing").asInt());
        assertEquals(0, row.path("submitted").asInt());
        assertEquals("Mock test 1", row.path("title").asText());
    }

    @Test
    void missingAfterDue() {
        Fx f = fixture();
        UUID a = give(f, Instant.now().plus(1, ChronoUnit.HOURS));
        assertEquals("PENDING", mine(f, a, f.learner));
        due(a, Instant.now().minus(1, ChronoUnit.MINUTES));
        assertEquals("MISSING", mine(f, a, f.learner));
        assertEquals("MISSING", mine(f, a, f.classmate));
        assertEquals("MISSING", statusFor(f, a, f.learner));
        assertEquals(0, su.queryForObject("select count(*) from assignment_submissions where assignment_id = ?", Integer.class, a), "MISSING is never stored");
    }

    @Test
    void whoMayGiveAndSee() {
        Fx f = fixture();
        Instant due = Instant.now().plus(2, ChronoUnit.DAYS);
        String path = "/api/orgs/" + f.org + "/classes/" + f.cls + "/assignments";
        // a learner gives nothing and sees no list; another teacher of the organisation neither
        assertEquals(403, post(path, Map.of("testVersionId", f.testVersion, "dueAt", due.toString()), f.learner, f.org).status());
        UUID otherTeacher = user("teacher2");
        grant(otherTeacher, f.org, "IELTS", "TEACHER");
        assertEquals(403, post(path, Map.of("testVersionId", f.testVersion, "dueAt", due.toString()), otherTeacher, f.org).status());
        UUID a = give(f, due);
        assertEquals(403, get(path, f.learner, f.org).status());
        assertEquals(403, get("/api/orgs/" + f.org + "/assignments/" + a, f.learner, f.org).status());
        assertEquals(200, get("/api/orgs/" + f.org + "/assignments/" + a, f.admin, f.org).status());
        // the learner of another organisation sees nothing of it; no organisation context, nothing
        UUID otherOrg = org("CENTER");
        UUID stranger = user("stranger");
        grant(stranger, otherOrg, null, "LEARNER");
        assertEquals(0, get("/api/me/assignments", stranger, otherOrg).body().size());
        assertEquals(0, get("/api/me/assignments", f.learner, null).body().size());
        assertEquals(404, get("/api/orgs/" + f.org + "/assignments/" + a, orgAdmin(otherOrg), otherOrg).status());
        // validation: one target, a future date, a published target
        assertEquals(422, post(path, Map.of("dueAt", due.toString()), f.teacher, f.org).status());
        assertEquals(422, post(path, Map.of("testVersionId", f.testVersion, "dueAt", Instant.now().minusSeconds(60).toString()), f.teacher, f.org).status());
        assertEquals(422, post(path, Map.of("testVersionId", UUID.randomUUID(), "dueAt", due.toString()), f.teacher, f.org).status());
        assertTrue(post(path, Map.of("lessonId", UUID.randomUUID(), "dueAt", due.toString()), f.teacher, f.org).problemType().endsWith("assignment-target-unavailable"));
    }
}
