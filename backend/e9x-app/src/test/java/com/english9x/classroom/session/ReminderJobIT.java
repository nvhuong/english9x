package com.english9x.classroom.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.english9x.billing.BillingIT;
import com.english9x.platform.jobs.api.RecurringJob;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** SESSION_REMINDER (spec 13): to the learners and the teacher of the class, one hour before a session starts, once. */
class ReminderJobIT extends BillingIT {

    @Autowired List<RecurringJob> schedules;

    /** The shared application clock also validates the tokens the next test mints: leave it at the real time. */
    @AfterEach
    void realTime() {
        clock.set(Instant.now());
    }

    private record Fx(UUID org, UUID teacher, UUID learner, UUID classmate, UUID outsider, UUID cls) {}

    private Fx fixture() {
        UUID org = org("CENTER");
        UUID admin = orgAdmin(org);
        UUID teacher = user("teacher");
        grant(teacher, org, "IELTS", "TEACHER");
        UUID learner = user("learner");
        grant(learner, org, null, "LEARNER");
        UUID classmate = user("classmate");
        grant(classmate, org, null, "LEARNER");
        UUID outsider = user("outsider");
        grant(outsider, org, null, "LEARNER");
        UUID cls = UUID.fromString(post("/api/orgs/" + org + "/classes", java.util.Map.of("name", "Lop " + UUID.randomUUID().toString().substring(0, 4), "teacherId", teacher), admin, org)
                .body().path("id").asText());
        assertEquals(200, post("/api/orgs/" + org + "/classes/" + cls + "/members", java.util.Map.of("userIds", List.of(learner, classmate)), teacher, org).status());
        return new Fx(org, teacher, learner, classmate, outsider, cls);
    }

    private UUID session(Fx f, Instant starts) {
        UUID id = UUID.randomUUID();
        su.update("insert into class_sessions(id,class_id,org_id,mode,starts_at,ends_at,room,created_by) values (?,?,?,'OFFLINE',?,?,'P1',?)",
                id, f.cls, f.org, Timestamp.from(starts), Timestamp.from(starts.plus(2, ChronoUnit.HOURS)), f.teacher);
        return id;
    }

    private int notices(UUID user, UUID session) {
        return su.queryForObject("select count(*) from notifications where user_id = ? and event_type = 'SESSION_REMINDER' and dedupe_key = ?", Integer.class, user, "session-reminder:" + session);
    }

    @Test
    void jobIsScheduled() {
        assertEquals(1, schedules.stream().filter(s -> s.type().equals("session_reminder")).count());
    }

    @Test
    void sendsOneHourBefore() {
        Fx f = fixture();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.set(now);
        UUID soon = session(f, now.plus(Duration.ofMinutes(50)));
        UUID later = session(f, now.plus(Duration.ofHours(3)));
        UUID past = session(f, now.minus(Duration.ofMinutes(10)));

        runJob("session_reminder");
        // the learners and the teacher of the class are told about the session starting within the hour; nobody else; nothing about the others
        for (UUID u : List.of(f.learner, f.classmate, f.teacher)) {
            assertEquals(1, notices(u, soon), "told: " + u);
            assertEquals(0, notices(u, later), "not yet: " + u);
            assertEquals(0, notices(u, past), "already started: " + u);
        }
        assertEquals(0, notices(f.outsider, soon));

        // running it again changes nothing (once per person and session)
        runJob("session_reminder");
        runJob("session_reminder");
        assertEquals(1, notices(f.learner, soon));

        // an hour and a bit later the 3-hour session has come within the hour: its turn
        clock.set(now.plus(Duration.ofMinutes(125)));
        runJob("session_reminder");
        assertEquals(1, notices(f.learner, later));
        assertEquals(1, notices(f.teacher, later));
        assertEquals(1, notices(f.learner, soon), "still the one");
        // the notice carries the session, not any content
        String data = su.queryForObject("select data::text from notifications where user_id = ? and dedupe_key = ?", String.class, f.learner, "session-reminder:" + later);
        org.junit.jupiter.api.Assertions.assertTrue(data.contains(later.toString()), data);
    }

    @Test
    void notBeforeTheHour() {
        Fx f = fixture();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.set(now);
        UUID s = session(f, now.plus(Duration.ofMinutes(61)));
        runJob("session_reminder");
        assertEquals(0, notices(f.learner, s), "61 minutes ahead is not within the hour");
        clock.set(now.plus(Duration.ofMinutes(2)));
        runJob("session_reminder");
        assertEquals(1, notices(f.learner, s));
    }
}
