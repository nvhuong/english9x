package com.english9x.analytics.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.analytics.progress.ProgressHarness;
import com.english9x.support.TestAuth;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * F49: the report of a class, as JSON and as an .xlsx. A class of the room's organisation with two learners (Ann works, Bao does not), a second organisation with a class and a learner
 * of its own, and one of Ann's attempts that belongs to that second organisation: none of it may reach the first organisation's report, in either format.
 */
class ClassReportIT extends ProgressHarness {

    private record Fx(UUID org, UUID admin, UUID teacher, UUID otherTeacher, UUID ann, UUID bao, UUID cls, UUID orgB, UUID adminB, UUID leaky, UUID clsB, String leakyName) {}

    private Fx fixture() {
        Room room = room();
        UUID org = room.staff().org();
        UUID admin = orgAdmin(org);
        UUID teacher = user("teacher");
        grant(teacher, org, "TOEIC", "TEACHER");
        UUID otherTeacher = user("teacher2");
        grant(otherTeacher, org, "TOEIC", "TEACHER");
        UUID ann = learner(room);
        UUID bao = learner(room);
        su.update("update users set display_name = ? where id = ?", "An Chăm Chỉ", ann);
        su.update("update users set display_name = ? where id = ?", "Bảo Lười", bao);
        UUID cls = UUID.fromString(post("/api/orgs/" + org + "/classes", Map.of("name", "Lớp báo cáo " + UUID.randomUUID().toString().substring(0, 4), "teacherId", teacher), admin, org)
                .body().path("id").asText());
        assertEquals(200, post("/api/orgs/" + org + "/classes/" + cls + "/members", Map.of("userIds", List.of(ann, bao)), teacher, org).status());

        // --- exams: Ann sits three; the third one is made in ANOTHER organisation (moved there below). Bao sits one and gets everything right.
        UUID orgB = org("CENTER");
        UUID adminB = orgAdmin(orgB);
        Sat s1 = sat(room, ann, "A", "C");
        Sat s2 = sat(room, ann, "A", "C");
        Sat s3 = sat(room, ann, "B", "C");
        sat(room, bao, "A", "D");
        score(ann, s1.attempt(), org, 600);
        score(ann, s2.attempt(), org, 450);
        score(ann, s3.attempt(), orgB, 990);
        for (String t : new String[] {"exam_attempts", "item_type_stats", "scores"}) {
            su.execute("alter table " + t + " disable trigger user");
        }
        su.update("update exam_attempts set owner_org_id = ? where id = ?", orgB, s3.attempt());
        su.update("update item_type_stats set owner_org_id = ? where attempt_id = ?", orgB, s3.attempt());
        for (String t : new String[] {"exam_attempts", "item_type_stats", "scores"}) {
            su.execute("alter table " + t + " enable trigger user");
        }

        // --- course work: a course of two lessons given to both as a seat of the organisation; Ann did both, Bao none
        Lessons c = course("Khoá báo cáo", 2);
        seatEntitlement(org, admin, ann, c.course(), Duration.ofDays(1), Duration.ofDays(90));
        seatEntitlement(org, admin, bao, c.course(), Duration.ofDays(1), Duration.ofDays(90));
        complete(ann, c.course(), c.refIds().get(0));
        complete(ann, c.course(), c.refIds().get(1));
        Instant now = clock.instant();
        // assignment 1: lesson 1, due in 3 days (Ann in time, Bao pending); assignment 2: lesson 2, due yesterday (Ann 12 hours late, Bao missing)
        su.update("insert into assignments(class_id,org_id,lesson_id,due_at,created_by) values (?,?,?,?,?)", cls, org, c.lessonIds().get(0), Timestamp.from(now.plus(Duration.ofDays(3))), admin);
        su.update("insert into assignments(class_id,org_id,lesson_id,due_at,created_by) values (?,?,?,?,?)", cls, org, c.lessonIds().get(1), Timestamp.from(now.minus(Duration.ofDays(1))), admin);
        su.update("update lesson_progress set completed_at = ? where user_id = ? and ref_id = ?", Timestamp.from(now.minus(Duration.ofHours(12))), ann, c.refIds().get(1));

        // --- attendance: Ann present + late (100%), Bao present + absent (50%)
        UUID s = session(cls, org, teacher, 3);
        UUID t = session(cls, org, teacher, 2);
        attendance(s, org, ann, "PRESENT", teacher);
        attendance(t, org, ann, "LATE", teacher);
        attendance(s, org, bao, "PRESENT", teacher);
        attendance(t, org, bao, "ABSENT", teacher);
        // --- study: Ann 25 minutes (1500 s over two days), Bao nothing
        su.update("insert into study_time_daily(user_id,day,seconds) values (?,?,900)", ann, LocalDate.now().minusDays(1));
        su.update("insert into study_time_daily(user_id,day,seconds) values (?,?,600)", ann, LocalDate.now());

        // --- the other organisation: a class with a learner who has a seat, work, attendance and a name that must never show in A's report
        UUID teacherB = user("teacherB");
        grant(teacherB, orgB, "TOEIC", "TEACHER");
        UUID leaky = user("leaky");
        String leakyName = "LEAKY-LEARNER-" + UUID.randomUUID().toString().substring(0, 6);
        su.update("update users set display_name = ? where id = ?", leakyName, leaky);
        grant(leaky, orgB, null, "LEARNER");
        UUID clsB = UUID.fromString(post("/api/orgs/" + orgB + "/classes", Map.of("name", "Lớp B", "teacherId", teacherB), adminB, orgB).body().path("id").asText());
        assertEquals(200, post("/api/orgs/" + orgB + "/classes/" + clsB + "/members", Map.of("userIds", List.of(leaky)), adminB, orgB).status());
        Lessons cb = course("Khoá của B", 1);
        seatEntitlement(orgB, adminB, leaky, cb.course(), Duration.ofDays(1), Duration.ofDays(90));
        complete(leaky, cb.course(), cb.refIds().get(0));
        attendance(session(clsB, orgB, teacherB, 1), orgB, leaky, "PRESENT", teacherB);
        return new Fx(org, admin, teacher, otherTeacher, ann, bao, cls, orgB, adminB, leaky, clsB, leakyName);
    }

    private void score(UUID user, UUID attempt, UUID org, double scaled) {
        su.update("insert into scores(attempt_id,user_id,owner_org_id,skill,kind,source,scaled) values (?,?,?,'OVERALL','OFFICIAL','AUTO',?)", attempt, user, org, scaled);
    }

    private UUID session(UUID cls, UUID org, UUID by, int daysAgo) {
        UUID id = UUID.randomUUID();
        Instant at = Instant.now().minus(daysAgo, java.time.temporal.ChronoUnit.DAYS);
        su.update("insert into class_sessions(id,class_id,org_id,mode,starts_at,ends_at,room,created_by) values (?,?,?,'OFFLINE',?,?,'P1',?)", id, cls, org, Timestamp.from(at),
                Timestamp.from(at.plus(2, java.time.temporal.ChronoUnit.HOURS)), by);
        return id;
    }

    private void attendance(UUID session, UUID org, UUID user, String status, UUID by) {
        su.update("insert into attendance(session_id,org_id,user_id,status,marked_by) values (?,?,?,?,?)", session, org, user, status, by);
    }

    private static String reportPath(UUID org, UUID cls) {
        return "/api/orgs/" + org + "/classes/" + cls + "/report";
    }

    private record Xlsx(int status, byte[] bytes, HttpHeaders headers) {}

    private Xlsx xlsx(String path, UUID asUser, UUID inOrg) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(TestAuth.accessToken(asUser));
        if (inOrg != null) {
            h.add("X-Org-Context", inOrg.toString());
        }
        ResponseEntity<byte[]> r = http.exchange(path, HttpMethod.GET, new HttpEntity<>(null, h), byte[].class);
        return new Xlsx(r.getStatusCode().value(), r.getBody() == null ? new byte[0] : r.getBody(), r.getHeaders());
    }

    private static Workbook open(Xlsx x) {
        try {
            return new XSSFWorkbook(new ByteArrayInputStream(x.bytes()));
        } catch (Exception e) {
            throw new AssertionError("not an xlsx (" + x.status() + "): " + new String(x.bytes(), 0, Math.min(200, x.bytes().length)), e);
        }
    }

    private static JsonNode learner(JsonNode report, UUID user) {
        for (JsonNode l : report.path("learners")) {
            if (l.path("userId").asText().equals(user.toString())) {
                return l;
            }
        }
        throw new AssertionError("not in the report: " + user + " in " + report);
    }

    private static Row rowOf(Sheet sheet, String displayName) {
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row != null && row.getCell(0) != null && displayName.equals(row.getCell(0).getStringCellValue())) {
                return row;
            }
        }
        throw new AssertionError("no row for " + displayName);
    }

    private static Cell cell(Row row, String column) {
        return row.getCell(ReportWorkbook.COLUMNS.indexOf(column));
    }

    private static void number(JsonNode expected, Row row, String column) {
        Cell c = cell(row, column);
        if (expected == null || expected.isMissingNode() || expected.isNull()) {
            assertTrue(c == null || c.getCellType() == CellType.BLANK, column + " should be empty, was " + c);
        } else {
            assertNotNull(c, column);
            assertEquals(CellType.NUMERIC, c.getCellType(), column);
            assertEquals(expected.asDouble(), c.getNumericCellValue(), 0.0, column);
        }
    }

    private static void text(String expected, Row row, String column) {
        Cell c = cell(row, column);
        if (expected == null || expected.isEmpty()) {
            assertTrue(c == null || c.getCellType() == CellType.BLANK, column + " should be empty, was " + c);
        } else {
            assertNotNull(c, column);
            assertEquals(expected, c.getStringCellValue(), column);
        }
    }

    private Set<String> allText(Workbook wb) {
        Set<String> out = new HashSet<>();
        for (Sheet sheet : wb) {
            for (Row row : sheet) {
                for (Cell c : row) {
                    out.add(c.getCellType() == CellType.STRING ? c.getStringCellValue() : String.valueOf(c.getNumericCellValue()));
                }
            }
        }
        return out;
    }

    @Test
    void figuresPerLearner() {
        Fx f = fixture();
        Res r = get(reportPath(f.org, f.cls), f.admin, f.org);
        assertEquals(200, r.status(), r.raw());
        JsonNode report = r.body();
        assertEquals(f.cls.toString(), report.path("classId").asText());
        assertEquals("CLASS", report.path("kind").asText());
        assertEquals(2, report.path("learners").size());

        JsonNode ann = learner(report, f.ann);
        assertEquals(100, ann.path("progressPercent").asInt(), "both lessons done");
        assertEquals(1, ann.path("courses").size(), "the seat of the organisation only: the personal order's course is not in this report");
        assertEquals(2, ann.path("assignments").path("total").asInt());
        assertEquals(1, ann.path("assignments").path("submitted").asInt());
        assertEquals(1, ann.path("assignments").path("late").asInt());
        assertEquals(0, ann.path("assignments").path("missing").asInt());
        assertEquals(450.0, ann.path("latestScore").path("value").asDouble());
        assertEquals("OFFICIAL", ann.path("latestScore").path("kind").asText());
        assertEquals(600.0, ann.path("bestScore").path("value").asDouble(), "the 990 was made in another organisation");
        assertEquals(1, ann.path("weakTypes").size());
        assertEquals("TOEIC_P5_INCOMPLETE_SENTENCE", ann.path("weakTypes").get(0).path("typeCode").asText());
        assertEquals(0, ann.path("weakTypes").get(0).path("correct").asInt());
        assertEquals(2, ann.path("weakTypes").get(0).path("total").asInt(), "the third attempt belongs to another organisation");
        assertEquals(1.0, ann.path("attendance").path("rate").asDouble());
        assertEquals(1, ann.path("attendance").path("late").asInt());
        assertEquals(25, ann.path("studyMinutes").asInt());
        assertTrue(ann.path("email").asText().contains("@"), "the admin reads addresses");

        JsonNode bao = learner(report, f.bao);
        assertEquals(0, bao.path("progressPercent").asInt());
        assertEquals(0, bao.path("assignments").path("submitted").asInt());
        assertEquals(1, bao.path("assignments").path("missing").asInt());
        assertEquals(1, bao.path("assignments").path("pending").asInt());
        assertEquals(0, bao.path("weakTypes").size(), "everything right");
        assertEquals(0.5, bao.path("attendance").path("rate").asDouble());
        assertEquals(0, bao.path("studyMinutes").asInt());

        // the same figures P4.5 shows for the learner: nothing is computed twice
        JsonNode p = get("/api/orgs/" + f.org + "/learners/" + f.ann + "/progress", f.admin, f.org).body();
        assertEquals(p.path("plans").get(0).path("courses").get(0).path("percent").asInt(), ann.path("courses").get(0).path("percent").asInt());
        assertEquals(p.path("scoreSeries").get(p.path("scoreSeries").size() - 1).path("score").asDouble(), ann.path("latestScore").path("value").asDouble());

        // the class's teacher reads it too, but not addresses
        JsonNode viaTeacher = get(reportPath(f.org, f.cls), f.teacher, f.org).body();
        assertTrue(learner(viaTeacher, f.ann).path("email").isNull());
    }

    @Test
    void excelMatchesJson() throws Exception {
        Fx f = fixture();
        JsonNode report = get(reportPath(f.org, f.cls), f.admin, f.org).body();
        Xlsx x = xlsx(reportPath(f.org, f.cls) + ".xlsx", f.admin, f.org);
        assertEquals(200, x.status());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", x.headers().getContentType().toString());
        assertTrue(x.headers().getFirst("Content-Disposition").startsWith("attachment"));
        try (Workbook wb = open(x)) {
            assertEquals(1, wb.getNumberOfSheets());
            Sheet sheet = wb.getSheetAt(0);
            assertEquals(ReportWorkbook.sheetName(report.path("className").asText()), sheet.getSheetName(), "the sheet is named after the class");
            Row head = sheet.getRow(0);
            for (int c = 0; c < ReportWorkbook.COLUMNS.size(); c++) {
                assertEquals(ReportWorkbook.Labels.of("vi").get(ReportWorkbook.COLUMNS.get(c)), head.getCell(c).getStringCellValue());
            }
            assertEquals(report.path("learners").size(), sheet.getLastRowNum(), "one row per learner");
            for (JsonNode l : report.path("learners")) {
                Row row = rowOf(sheet, l.path("displayName").asText());
                text(l.path("email").asText(null), row, "email");
                number(l.path("progressPercent"), row, "progress");
                List<String> courses = new ArrayList<>();
                for (JsonNode c : l.path("courses")) {
                    courses.add((c.path("code").isNull() ? "" : c.path("code").asText() + " ") + c.path("title").asText() + " " + c.path("percent").asInt() + "%");
                }
                text(String.join("; ", courses), row, "courses");
                number(l.path("assignments").path("submitted"), row, "submitted");
                number(l.path("assignments").path("late"), row, "late");
                number(l.path("assignments").path("missing"), row, "missing");
                number(l.path("assignments").path("pending"), row, "pending");
                number(l.path("latestScore").path("value"), row, "latest");
                number(l.path("bestScore").path("value"), row, "best");
                if (!l.path("latestScore").isNull()) {
                    text("OFFICIAL".equals(l.path("latestScore").path("kind").asText()) ? "Chính thức" : "Tạm thời", row, "latestKind");
                }
                List<String> weak = new ArrayList<>();
                for (JsonNode w : l.path("weakTypes")) {
                    weak.add(w.path("typeName").asText() + " (" + w.path("correct").asInt() + "/" + w.path("total").asInt() + ")");
                }
                text(String.join("; ", weak), row, "weak");
                number(l.path("attendance").path("rate"), row, "attendance");
                number(l.path("attendance").path("present"), row, "present");
                number(l.path("attendance").path("late"), row, "lateAtt");
                number(l.path("attendance").path("absent"), row, "absent");
                number(l.path("attendance").path("excused"), row, "excused");
                number(l.path("studyMinutes"), row, "minutes");
            }
            // spot-check literal numbers so a symmetric bug cannot pass
            Row ann = rowOf(sheet, "An Chăm Chỉ");
            assertEquals(100.0, cell(ann, "progress").getNumericCellValue());
            assertEquals(450.0, cell(ann, "latest").getNumericCellValue());
            assertEquals(600.0, cell(ann, "best").getNumericCellValue());
            assertEquals(25.0, cell(ann, "minutes").getNumericCellValue());
            assertEquals(0.5, cell(rowOf(sheet, "Bảo Lười"), "attendance").getNumericCellValue());
        }
        // the labels follow the language asked for
        try (Workbook en = open(xlsx(reportPath(f.org, f.cls) + ".xlsx?lang=en", f.admin, f.org))) {
            assertEquals("Learner", en.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("Official", cell(rowOf(en.getSheetAt(0), "An Chăm Chỉ"), "latestKind").getStringCellValue());
        }
        assertTrue(su.queryForObject("select count(*) from audit_log where action = 'report.export' and target_id = ?", Integer.class, f.cls.toString()) >= 2, "each export is audited");
    }

    @Test
    void onlyOwnOrgData() throws Exception {
        Fx f = fixture();
        Res json = get(reportPath(f.org, f.cls), f.admin, f.org);
        Xlsx x = xlsx(reportPath(f.org, f.cls) + ".xlsx", f.admin, f.org);
        assertEquals(200, x.status());
        for (String mark : new String[] {f.leaky.toString(), f.leakyName, "Khoá của B", "Lớp B", f.orgB.toString(), f.clsB.toString()}) {
            assertFalse(json.raw().contains(mark), "JSON leaks " + mark);
        }
        try (Workbook wb = open(x)) {
            Set<String> cells = allText(wb);
            for (String c : cells) {
                for (String mark : new String[] {f.leakyName, "Khoá của B", "Lớp B", "LEAKY"}) {
                    assertFalse(c.contains(mark), "the xlsx leaks " + mark + " in " + c);
                }
            }
            assertEquals(3, wb.getSheetAt(0).getPhysicalNumberOfRows(), "header + the two learners of the class");
            assertEquals(Set.of("An Chăm Chỉ", "Bảo Lười"), Set.of(wb.getSheetAt(0).getRow(1).getCell(0).getStringCellValue(), wb.getSheetAt(0).getRow(2).getCell(0).getStringCellValue()));
            // Ann's attempt of the other organisation (990, one more wrong P5) is in neither her scores nor her weak types
            Row ann = rowOf(wb.getSheetAt(0), "An Chăm Chỉ");
            assertEquals(600.0, cell(ann, "best").getNumericCellValue());
            assertTrue(cell(ann, "weak").getStringCellValue().endsWith("(0/2)"), cell(ann, "weak").getStringCellValue());
        }
        // and the other way round: B's report holds B's learner only
        Res jb = get(reportPath(f.orgB, f.clsB), f.adminB, f.orgB);
        assertEquals(200, jb.status(), jb.raw());
        assertEquals(1, jb.body().path("learners").size());
        assertEquals(f.leaky.toString(), jb.body().path("learners").get(0).path("userId").asText());
        assertFalse(jb.raw().contains(f.ann.toString()) || jb.raw().contains(f.bao.toString()));
        try (Workbook wb = open(xlsx(reportPath(f.orgB, f.clsB) + ".xlsx", f.adminB, f.orgB))) {
            assertEquals(2, wb.getSheetAt(0).getPhysicalNumberOfRows());
            for (String c : allText(wb)) {
                assertFalse(c.contains("Chăm Chỉ") || c.contains("Bảo Lười"), c);
            }
        }
        // a person who holds roles in both organisations sees one organisation's class per context only
        assertEquals(404, get(reportPath(f.org, f.cls), f.adminB, f.orgB).status(), "A's class in B's context");
        assertEquals(404, get(reportPath(f.orgB, f.clsB), f.admin, f.org).status(), "B's class in A's context");
        assertEquals(404, get(reportPath(f.org, f.clsB), f.admin, f.org).status(), "B's class under A's path");
        assertEquals(404, xlsx(reportPath(f.org, f.clsB) + ".xlsx", f.admin, f.org).status(), "B's class under A's path, xlsx");
        assertEquals(404, xlsx(reportPath(f.org, f.cls) + ".xlsx", f.adminB, f.orgB).status());
    }

    @Test
    void whoMayRead() {
        Fx f = fixture();
        UUID outsider = user("outsider");
        String json = reportPath(f.org, f.cls);
        for (String path : new String[] {json, json + ".xlsx"}) {
            assertEquals(200, xlsx(path, f.admin, f.org).status(), path);
            assertEquals(200, xlsx(path, f.teacher, f.org).status(), path + " (the class's teacher)");
            assertEquals(403, xlsx(path, f.otherTeacher, f.org).status(), path + " (a teacher of the organisation, not of this class)");
            assertEquals(403, xlsx(path, f.ann, f.org).status(), path + " (a learner of the class)");
            assertEquals(403, xlsx(path, f.admin, null).status(), path + " (no organisation context)");
            assertEquals(404, xlsx(path, f.adminB, f.orgB).status(), path + " (another organisation)");
            assertTrue(xlsx(path, outsider, f.org).status() >= 400, path + " (not in the organisation)");
        }
        assertEquals(401, call(HttpMethod.GET, json, null, (UUID) null, null).status());
        assertEquals(404, get(reportPath(f.org, UUID.randomUUID()), f.admin, f.org).status(), "no such class");
    }
}
