package com.english9x.analytics.aimonitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.analytics.results.ResultsHarness;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** F56: cost per day and person, the alert above the daily threshold (default 500000 VND), failed jobs, AI-vs-teacher deviation; SYS_ADMIN only. */
class AiMonitorIT extends ResultsHarness {

    private static final String DAY1 = "2020-01-10";
    private static final String DAY2 = "2020-01-11";

    private void evaluation(UUID attempt, UUID question, UUID user, String cost, String at, double band) {
        su.update("insert into ai_evaluations(attempt_id,question_id,user_id,model,input_tokens,output_tokens,cost_vnd,band,result,created_at) values (?,?,?,'gemini',100,50,?::numeric,?,'{}'::jsonb,?::timestamptz)",
                attempt, question, user, cost, band, at);
    }

    private JsonNode day(JsonNode body, String date) {
        for (JsonNode d : body.path("days")) {
            if (date.equals(d.path("day").asText())) {
                return d;
            }
        }
        throw new AssertionError("no day " + date + " in " + body);
    }

    @Test
    void costAlertRaisedAboveThreshold() {
        Room room = room();
        UUID a = learner(room);
        UUID b = learner(room);
        UUID attemptA = attemptId(startOk(room, a));
        UUID attemptB = attemptId(startOk(room, b));
        // 2020-01-10 (Vietnam): 300000 + 250000 = 550000 > 500000; 2020-01-11: 400000 <= 500000
        evaluation(attemptA, UUID.randomUUID(), a, "300000", "2020-01-10T05:00:00Z", 6);
        evaluation(attemptB, UUID.randomUUID(), b, "250000", "2020-01-10T06:00:00Z", 6);
        evaluation(attemptA, UUID.randomUUID(), a, "400000", "2020-01-11T05:00:00Z", 6);
        su.update("insert into jobs(id,type,payload,status,run_at,finished_at,attempts,last_error) values (?,'ai_grade','{\"secret\":\"payload-not-shown\"}','FAILED',?::timestamptz,?::timestamptz,3,'Gemini 503')",
                UUID.randomUUID(), "2020-01-10T05:00:00Z", "2020-01-10T05:01:00Z");
        UUID sys = sysAdmin();

        Res r = get("/api/admin/ai-monitor?from=" + DAY1 + "&to=2020-01-12", sys, null);
        assertEquals(200, r.status(), r.raw());
        JsonNode body = r.body();
        assertTrue(body.path("costAlert").asBoolean(), r.raw());
        assertEquals(500000, body.path("dailyCostAlert").asInt());
        assertEquals(1, body.path("alertDays").size());
        assertEquals(DAY1, body.path("alertDays").get(0).asText());
        assertEquals(3, body.path("days").size());
        assertEquals(550000, day(body, DAY1).path("costVnd").asInt());
        assertTrue(day(body, DAY1).path("overThreshold").asBoolean());
        assertEquals(2, day(body, DAY1).path("evaluations").asInt());
        assertEquals(300, day(body, DAY1).path("tokens").asInt());
        assertEquals(400000, day(body, DAY2).path("costVnd").asInt());
        assertFalse(day(body, DAY2).path("overThreshold").asBoolean());
        assertEquals(0, day(body, "2020-01-12").path("costVnd").asInt());
        assertEquals(950000, body.path("totalCostVnd").asInt());
        assertEquals(3, body.path("costByDayAndPerson").size());
        assertEquals(emailOf(a), body.path("costByDayAndPerson").get(0).path("email").asText());
        assertEquals(1, body.path("failedJobs").size());
        assertEquals("Gemini 503", body.path("failedJobs").get(0).path("lastError").asText());
        assertFalse(r.raw().contains("payload-not-shown"));

        // a window with only the cheaper day raises nothing
        JsonNode calm = get("/api/admin/ai-monitor?from=" + DAY2 + "&to=" + DAY2, sys, null).body();
        assertFalse(calm.path("costAlert").asBoolean());
        assertEquals(0, calm.path("alertDays").size());
        assertEquals(0, calm.path("failedJobs").size());
    }

    @Test
    void deviationPerQuestionType() {
        Room room = room();
        UUID learner = learner(room);
        Sat s = start(room, learner, "A", "D");
        UUID question = su.queryForObject("select question_id from attempt_questions where attempt_id = ? and section_code = 'P1'", UUID.class, s.attempt());
        evaluation(s.attempt(), question, learner, "10", "2020-02-10T05:00:00Z", 5);
        UUID queue = UUID.randomUUID();
        su.update("insert into grading_queue_items(id,attempt_id,user_id,owner_org_id,grading_org_id,exam_code,reason) values (?,?,?,?,?,'TOEIC','POLICY')", queue, s.attempt(), learner,
                null, null);
        UUID teacher = user("dev-teacher");
        su.update("insert into teacher_reviews(queue_item_id,attempt_id,user_id,skill,teacher_id,criteria,comment,band,ai_band,deviation,created_at) "
                + "values (?,?,?,'LISTENING',?,'{}'::jsonb,'',6.5,5,1.5,'2020-02-10T06:00:00Z')", queue, s.attempt(), learner, teacher);
        JsonNode body = get("/api/admin/ai-monitor?from=2020-02-10&to=2020-02-10", sysAdmin(), null).body();
        assertEquals(1, body.path("deviation").size(), body.toString());
        JsonNode d = body.path("deviation").get(0);
        assertEquals("TOEIC_P1_PHOTO", d.path("typeCode").asText());
        assertEquals(1, d.path("samples").asInt());
        assertEquals(1.5, d.path("meanDeviation").asDouble());
        assertEquals(1.5, d.path("meanAbsDeviation").asDouble());
    }

    @Test
    void onlySysAdminReadsTheMonitor() {
        Room room = room();
        UUID org = room.staff().org();
        UUID orgAdmin = orgAdmin(org);
        UUID teacher = user("monitor-teacher");
        grant(teacher, org, "TOEIC", "TEACHER");
        UUID learner = learner(room);
        String path = "/api/admin/ai-monitor?from=" + DAY1 + "&to=" + DAY1;
        assertEquals(403, get(path, learner, null).status());
        assertEquals(403, get(path, learner, org).status());
        assertEquals(403, get(path, orgAdmin, org).status());
        assertEquals(403, get(path, orgAdmin, null).status());
        assertEquals(403, get(path, teacher, org).status());
        assertEquals(403, get(path, room.staff().author(), null).status());
        assertEquals(200, get(path, sysAdmin(), null).status());
        assertEquals(400, get("/api/admin/ai-monitor?from=2020-02-10&to=2020-02-01", sysAdmin(), null).status());
    }
}
