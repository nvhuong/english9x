package com.english9x.classroom.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.english9x.org.OrgIT;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** F47: the class schedule: online sessions with an https link, offline ones in a room, who sees what, the personal schedule. */
class SessionIT extends OrgIT {

    private record Fx(UUID org, UUID admin, UUID teacher, UUID otherTeacher, UUID learner, UUID outsider, UUID cls) {}

    private Fx fixture() {
        UUID org = org("CENTER");
        UUID admin = orgAdmin(org);
        UUID teacher = user("teacher");
        grant(teacher, org, "IELTS", "TEACHER");
        UUID otherTeacher = user("teacher2");
        grant(otherTeacher, org, "IELTS", "TEACHER");
        UUID learner = user("learner");
        grant(learner, org, null, "LEARNER");
        UUID outsider = user("outsider");
        grant(outsider, org, null, "LEARNER");
        UUID cls = UUID.fromString(post("/api/orgs/" + org + "/classes", Map.of("name", "Lop " + UUID.randomUUID().toString().substring(0, 4), "teacherId", teacher), admin, org)
                .body().path("id").asText());
        assertEquals(200, post("/api/orgs/" + org + "/classes/" + cls + "/members", Map.of("userIds", List.of(learner)), teacher, org).status());
        return new Fx(org, admin, teacher, otherTeacher, learner, outsider, cls);
    }

    private static Map<String, Object> body(String mode, Instant starts, String room, String url) {
        Map<String, Object> m = new HashMap<>();
        m.put("mode", mode);
        m.put("startsAt", starts.toString());
        m.put("endsAt", starts.plus(2, ChronoUnit.HOURS).toString());
        if (room != null) {
            m.put("room", room);
        }
        if (url != null) {
            m.put("meetingUrl", url);
        }
        return m;
    }

    private String path(Fx f) {
        return "/api/orgs/" + f.org + "/classes/" + f.cls + "/sessions";
    }

    @Test
    void onlineRequiresHttpsMeetingUrl() {
        Fx f = fixture();
        Instant at = Instant.now().plus(2, ChronoUnit.DAYS);
        // no link, a plain http link, another scheme, no host, junk: refused with the field named
        for (String bad : new String[] {null, "", "   ", "http://meet.example.org/x", "ftp://meet.example.org/x", "javascript:alert(1)", "https://", "not a url", "//meet.example.org"}) {
            Res r = post(path(f), body("ONLINE", at, null, bad), f.teacher, f.org);
            assertEquals(422, r.status(), "[" + bad + "] " + r.raw());
            assertEquals("meetingUrl", r.body().path("errors").path(0).path("field").asText(), r.raw());
        }
        assertEquals(0, su.queryForObject("select count(*) from class_sessions where class_id = ?", Integer.class, f.cls));
        // an https link of any domain is accepted
        for (String ok : new String[] {"https://meet.google.com/abc-defg-hij", "https://zoom.us/j/123?pwd=x", "https://lop.example.vn/phong-1"}) {
            Res r = post(path(f), body("ONLINE", at, null, ok), f.teacher, f.org);
            assertEquals(201, r.status(), r.raw());
            assertEquals("ONLINE", r.body().path("mode").asText());
            assertEquals(ok, r.body().path("meetingUrl").asText());
        }
        // an offline session takes a room and no link
        Res room = post(path(f), body("OFFLINE", at, "Phòng 301", null), f.teacher, f.org);
        assertEquals(201, room.status(), room.raw());
        assertEquals("Phòng 301", room.body().path("room").asText());
        assertEquals(422, post(path(f), body("OFFLINE", at, "P1", "https://meet.example.org/x"), f.teacher, f.org).status());
        // it must end after it starts
        Map<String, Object> reversed = body("OFFLINE", at, "P1", null);
        reversed.put("endsAt", at.minus(1, ChronoUnit.HOURS).toString());
        assertEquals(422, post(path(f), reversed, f.teacher, f.org).status());
    }

    @Test
    void rolesAndVisibility() {
        Fx f = fixture();
        Instant at = Instant.now().plus(2, ChronoUnit.DAYS);
        // a learner and another teacher write nothing; the admin and the class's teacher do
        assertEquals(403, post(path(f), body("OFFLINE", at, "P1", null), f.learner, f.org).status());
        assertEquals(403, post(path(f), body("OFFLINE", at, "P1", null), f.otherTeacher, f.org).status());
        assertEquals(201, post(path(f), body("OFFLINE", at, "P1", null), f.admin, f.org).status());
        assertEquals(201, post(path(f), body("ONLINE", at.plus(1, ChronoUnit.DAYS), null, "https://meet.example.org/x"), f.teacher, f.org).status());
        // the class's sessions: managers and the member see both; a member of the organisation outside the class sees none; the audit has them
        assertEquals(2, get(path(f), f.teacher, f.org).body().size());
        assertEquals(2, get(path(f), f.admin, f.org).body().size());
        assertEquals(2, get(path(f), f.learner, f.org).body().size());
        assertEquals(0, get(path(f), f.outsider, f.org).body().size());
        assertEquals(2, su.queryForObject("select count(*) from audit_log where action = 'session.create' and detail->>'classId' = ?", Integer.class, f.cls.toString()));
        // another organisation's context reaches nothing
        UUID other = org("CENTER");
        assertEquals(404, get("/api/orgs/" + f.org + "/classes/" + f.cls + "/sessions", orgAdmin(other), other).status());
    }

    @Test
    void myScheduleIsMineInTheContextAndTheRange() {
        Fx f = fixture();
        Instant d2 = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant d9 = d2.plus(7, ChronoUnit.DAYS);
        assertEquals(201, post(path(f), body("OFFLINE", d2, "P1", null), f.teacher, f.org).status());
        assertEquals(201, post(path(f), body("ONLINE", d9, null, "https://meet.example.org/x"), f.teacher, f.org).status());
        String all = "/api/me/schedule?from=" + Instant.now() + "&to=" + Instant.now().plus(30, ChronoUnit.DAYS);

        Res learner = get(all, f.learner, f.org);
        assertEquals(200, learner.status(), learner.raw());
        assertEquals(2, learner.body().size());
        assertEquals("LEARNER", learner.body().path(0).path("role").asText());
        assertEquals("P1", learner.body().path(0).path("room").asText());
        assertEquals(2, get(all, f.teacher, f.org).body().size());
        assertEquals("TEACHER", get(all, f.teacher, f.org).body().path(0).path("role").asText());
        // not in the class, not its teacher: nothing (the admin of the organisation neither: it is a personal schedule)
        assertEquals(0, get(all, f.outsider, f.org).body().size());
        assertEquals(0, get(all, f.otherTeacher, f.org).body().size());
        assertEquals(0, get(all, f.admin, f.org).body().size());
        // outside the organisation's context: nothing
        assertEquals(0, call(org.springframework.http.HttpMethod.GET, all, null, f.learner, null).body().size());
        // the range: [from, to)
        Res narrow = get("/api/me/schedule?from=" + d2.minus(1, ChronoUnit.HOURS) + "&to=" + d2.plus(1, ChronoUnit.DAYS), f.learner, f.org);
        assertEquals(1, narrow.body().size());
        assertEquals(0, get("/api/me/schedule?from=" + d2.plus(1, ChronoUnit.DAYS) + "&to=" + d9, f.learner, f.org).body().size(), "to is exclusive, the first is before from");
        assertEquals(422, get("/api/me/schedule?from=" + d9 + "&to=" + d2, f.learner, f.org).status());
        assertEquals(422, get("/api/me/schedule?from=" + d2 + "&to=" + d2.plus(400, ChronoUnit.DAYS), f.learner, f.org).status());
        // without a range: from now, one month
        assertEquals(2, get("/api/me/schedule", f.learner, f.org).body().size());
    }
}
