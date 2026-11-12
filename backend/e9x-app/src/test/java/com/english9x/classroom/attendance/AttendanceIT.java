package com.english9x.classroom.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.OrgIT;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** F48: attendance in four states and the attendance rate worked out per learner. */
class AttendanceIT extends OrgIT {

    private record Fx(UUID org, UUID admin, UUID teacher, UUID otherTeacher, UUID ann, UUID bao, UUID chi, UUID outsider, UUID cls) {}

    private Fx fixture() {
        UUID org = org("CENTER");
        UUID admin = orgAdmin(org);
        UUID teacher = user("teacher");
        grant(teacher, org, "IELTS", "TEACHER");
        UUID otherTeacher = user("teacher2");
        grant(otherTeacher, org, "IELTS", "TEACHER");
        UUID ann = user("ann");
        UUID bao = user("bao");
        UUID chi = user("chi");
        UUID outsider = user("outsider");
        for (UUID u : List.of(ann, bao, chi, outsider)) {
            grant(u, org, null, "LEARNER");
        }
        UUID cls = UUID.fromString(post("/api/orgs/" + org + "/classes", Map.of("name", "Lop " + UUID.randomUUID().toString().substring(0, 4), "teacherId", teacher), admin, org)
                .body().path("id").asText());
        assertEquals(200, post("/api/orgs/" + org + "/classes/" + cls + "/members", Map.of("userIds", List.of(ann, bao, chi)), teacher, org).status());
        return new Fx(org, admin, teacher, otherTeacher, ann, bao, chi, outsider, cls);
    }

    private UUID session(Fx f, int daysAgo) {
        UUID id = UUID.randomUUID();
        Instant at = Instant.now().minus(daysAgo, ChronoUnit.DAYS);
        su.update("insert into class_sessions(id,class_id,org_id,mode,starts_at,ends_at,room,created_by) values (?,?,?,'OFFLINE',?,?,'P1',?)",
                id, f.cls, f.org, Timestamp.from(at), Timestamp.from(at.plus(2, ChronoUnit.HOURS)), f.teacher);
        return id;
    }

    private String path(Fx f, UUID session) {
        return "/api/orgs/" + f.org + "/classes/" + f.cls + "/sessions/" + session + "/attendance";
    }

    private Res put(Fx f, UUID session, UUID by, Object marks) {
        return call(HttpMethod.PUT, path(f, session), marks, by, f.org);
    }

    private static Map<String, Object> mark(UUID user, String status, String note) {
        return note == null ? Map.of("userId", user, "status", status) : Map.of("userId", user, "status", status, "note", note);
    }

    private com.fasterxml.jackson.databind.JsonNode row(Res report, UUID user) {
        for (var r : report.body().path("rows")) {
            if (r.path("userId").asText().equals(user.toString())) {
                return r;
            }
        }
        throw new AssertionError("not in the report: " + report.raw());
    }

    @Test
    void rateComputedPerLearner() {
        Fx f = fixture();
        UUID s1 = session(f, 4);
        UUID s2 = session(f, 3);
        UUID s3 = session(f, 2);
        UUID s4 = session(f, 1);
        // Ann: PRESENT, LATE, ABSENT, EXCUSED  -> 2 of 3 (the excused one leaves the rate)
        // Bao: PRESENT, ABSENT, ABSENT, (s4 not marked) -> 1 of 3
        // Chi: only EXCUSED -> no rate; later PRESENT on s2
        assertEquals(200, put(f, s1, f.teacher, List.of(mark(f.ann, "PRESENT", null), mark(f.bao, "PRESENT", null), mark(f.chi, "EXCUSED", "ốm"))).status());
        assertEquals(200, put(f, s2, f.teacher, List.of(mark(f.ann, "LATE", "10 phút"), mark(f.bao, "ABSENT", null))).status());
        assertEquals(200, put(f, s3, f.teacher, List.of(mark(f.ann, "ABSENT", null), mark(f.bao, "ABSENT", null))).status());
        assertEquals(200, put(f, s4, f.teacher, List.of(mark(f.ann, "EXCUSED", null))).status());

        Res report = get("/api/orgs/" + f.org + "/classes/" + f.cls + "/attendance", f.teacher, f.org);
        assertEquals(200, report.status(), report.raw());
        assertEquals(3, report.body().path("rows").size());
        var ann = row(report, f.ann);
        assertEquals(1, ann.path("present").asInt());
        assertEquals(1, ann.path("late").asInt());
        assertEquals(1, ann.path("absent").asInt());
        assertEquals(1, ann.path("excused").asInt());
        assertEquals(2.0 / 3.0, ann.path("rate").asDouble(), 1e-9);
        var bao = row(report, f.bao);
        assertEquals(1, bao.path("present").asInt());
        assertEquals(2, bao.path("absent").asInt());
        assertEquals(1.0 / 3.0, bao.path("rate").asDouble(), 1e-9);
        var chi = row(report, f.chi);
        assertEquals(1, chi.path("excused").asInt());
        assertTrue(chi.path("rate").isNull(), "only an excused session: no rate, not 0%");

        // a mark is corrected by sending the new one: Bao was in fact there on the third day
        assertEquals(200, put(f, s3, f.teacher, List.of(mark(f.bao, "PRESENT", "đã sửa"))).status());
        Res again = get("/api/orgs/" + f.org + "/classes/" + f.cls + "/attendance", f.admin, f.org);
        assertEquals(2.0 / 3.0, row(again, f.bao).path("rate").asDouble(), 1e-9);
        assertEquals(2.0 / 3.0, row(again, f.ann).path("rate").asDouble(), 1e-9, "Ann is not touched by the correction");
        assertEquals(1, su.queryForObject("select count(*) from attendance where session_id = ? and user_id = ? and status = 'PRESENT' and note = 'đã sửa'", Integer.class, s3, f.bao));
        assertTrue(again.body().path("rows").path(0).path("email").asText().contains("@"), "the admin reads addresses");
        assertTrue(row(report, f.ann).path("email").isNull(), "a teacher does not");

        // the sheet of a session: every member, the unmarked ones with a null status
        Res sheet = get(path(f, s2), f.teacher, f.org);
        assertEquals(200, sheet.status(), sheet.raw());
        assertEquals(3, sheet.body().path("rows").size());
        for (var r : sheet.body().path("rows")) {
            String u = r.path("userId").asText();
            if (u.equals(f.chi.toString())) {
                assertTrue(r.path("status").isNull());
            }
            if (u.equals(f.ann.toString())) {
                assertEquals("LATE", r.path("status").asText());
                assertEquals("10 phút", r.path("note").asText());
            }
        }
        assertEquals(2, su.queryForObject("select count(*) from audit_log where action = 'attendance.mark' and target_id = ?", Integer.class, s3.toString()), "the mark and its correction");
    }

    @Test
    void whoMayMarkAndWhom() {
        Fx f = fixture();
        UUID s = session(f, 1);
        List<Map<String, Object>> one = List.of(mark(f.ann, "PRESENT", null));
        // a learner (even marking themselves) and another teacher mark nothing; the admin and the class's teacher do
        assertEquals(403, put(f, s, f.ann, one).status());
        assertEquals(403, put(f, s, f.otherTeacher, one).status());
        assertEquals(0, su.queryForObject("select count(*) from attendance where session_id = ?", Integer.class, s));
        assertEquals(200, put(f, s, f.admin, one).status());
        assertEquals(200, put(f, s, f.teacher, List.of(mark(f.bao, "ABSENT", null))).status());
        // the sheet and the report are for managers only
        assertEquals(403, get(path(f, s), f.ann, f.org).status());
        assertEquals(403, get("/api/orgs/" + f.org + "/classes/" + f.cls + "/attendance", f.ann, f.org).status());
        assertEquals(403, get(path(f, s), f.otherTeacher, f.org).status());
        // somebody who is not in the class cannot be marked, and nothing of the request is kept
        Res bad = put(f, s, f.teacher, List.of(mark(f.chi, "PRESENT", null), mark(f.outsider, "PRESENT", null)));
        assertEquals(422, bad.status(), bad.raw());
        assertEquals(0, su.queryForObject("select count(*) from attendance where session_id = ? and user_id = ?", Integer.class, s, f.chi));
        // bad bodies: no marks, an unknown status, a missing user
        assertEquals(422, put(f, s, f.teacher, List.of()).status());
        assertEquals(400, put(f, s, f.teacher, List.of(Map.of("userId", f.ann, "status", "MAYBE"))).status());
        assertEquals(400, put(f, s, f.teacher, List.of(Map.of("status", "PRESENT"))).status());
        // a session of no such class / no such session
        assertEquals(404, put(f, UUID.randomUUID(), f.teacher, one).status());
        UUID other = org("CENTER");
        assertEquals(404, call(HttpMethod.PUT, path(f, s), one, orgAdmin(other), other).status());
        // a learner of the class sees their own mark in their schedule
        Res mine = get("/api/me/schedule?from=" + Instant.now().minus(3, ChronoUnit.DAYS) + "&to=" + Instant.now().plus(1, ChronoUnit.DAYS), f.ann, f.org);
        assertEquals("PRESENT", mine.body().path(0).path("attendance").asText(), mine.raw());
    }
}
