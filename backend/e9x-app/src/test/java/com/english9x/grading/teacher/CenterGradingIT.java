package com.english9x.grading.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.exam.internal.ExamIT;
import com.english9x.grading.ai.AiStore.Attempt;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/**
 * Centre grading configuration (F50) through the real stack: the ORG_ADMIN of a CENTER sets the side with {@code PUT /api/orgs/{id}/grading-settings};
 * the queue then routes the centre's learners' work to the side chosen (the database's {@code grading_queue_side}, exercised by {@link TeacherQueueIT}).
 * An ENTERPRISE has no teachers (spec §4), so a centre grader or a teacher mode is refused with a 400 at the service, not only at the database (db-test 37).
 */
class CenterGradingIT extends ExamIT {

    @Autowired GradingQueueService queue;

    private UUID submittedAttempt(UUID learner, UUID org) {
        UUID attempt = UUID.randomUUID();
        su.update("insert into exams(code,name) values ('IELTS','IELTS') on conflict do nothing");
        su.update("insert into exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,submitted_at,interrupted,submit_reason) "
                + "values (?,?,?,?,'SUBMITTED','IELTS',2,now(),false,'LEARNER')", attempt, learner, org, UUID.randomUUID());
        su.update("insert into attempt_gradings(attempt_id,user_id,owner_org_id,interrupted) values (?,?,?,false)", attempt, learner, org);
        return attempt;
    }

    private UUID enqueue(UUID attempt) {
        Map<String, Object> row = su.queryForMap("select user_id, owner_org_id from exam_attempts where id = ?", attempt);
        Attempt a = queue.find(attempt, (UUID) row.get("user_id"), (UUID) row.get("owner_org_id")).orElseThrow();
        assertTrue(queue.enqueue(a, GradingQueueService.Reason.POLICY), "queued by this call");
        return su.queryForObject("select id from grading_queue_items where attempt_id = ?", UUID.class, attempt);
    }

    private Res setSettings(UUID org, UUID actor, String grader, String mode) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("grader", grader);
        if (mode != null) {
            body.put("mode", mode);
        }
        return call(HttpMethod.PUT, "/api/orgs/" + org + "/grading-settings", body, actor, org);
    }

    @Test
    void centerTeacherQueueWhenCenterGrader() {
        UUID center = org("CENTER");
        UUID admin = orgAdmin(center);
        UUID learner = user("cg-learner");
        member(center, learner);

        Res put = setSettings(center, admin, "CENTER", "TEACHER");
        assertEquals(200, put.status(), put.raw());
        assertEquals("CENTER", put.body().path("grader").asText());
        assertEquals("CENTER", su.queryForObject("select side from org_grading_sides where org_id = ?", String.class, center));

        UUID item = enqueue(submittedAttempt(learner, center));
        assertEquals(center, su.queryForObject("select grading_org_id from grading_queue_items where id = ?", UUID.class, item),
                "the centre's own teachers grade, so the item is on the centre's side");
    }

    @Test
    void english9xQueueWhenEnglish9xGrader() {
        UUID center = org("CENTER");
        UUID admin = orgAdmin(center);
        UUID learner = user("cg-e-learner");
        member(center, learner);

        Res put = setSettings(center, admin, "ENGLISH9X", null);
        assertEquals(200, put.status(), put.raw());
        assertEquals("ENGLISH9X", su.queryForObject("select side from org_grading_sides where org_id = ?", String.class, center));

        UUID item = enqueue(submittedAttempt(learner, center));
        assertNull(su.queryForObject("select grading_org_id from grading_queue_items where id = ?", UUID.class, item),
                "the centre chose English9x, so the item is on English9x's side");
    }

    @Test
    void enterpriseCannotPickCenterGraderOrTeacherMode() {
        UUID ent = org("ENTERPRISE");
        UUID admin = orgAdmin(ent);

        assertEquals(400, setSettings(ent, admin, "CENTER", null).status(), "an enterprise has no own teachers");
        assertEquals(400, setSettings(ent, admin, "ENGLISH9X", "TEACHER").status(), "nor a teacher mode");
        assertEquals(400, setSettings(ent, admin, "ENGLISH9X", "BOTH").status());
        assertEquals(0, su.queryForObject("select count(*) from org_grading_sides where org_id = ?", Integer.class, ent), "nothing was written");

        Res ok = setSettings(ent, admin, "ENGLISH9X", "AI");
        assertEquals(200, ok.status(), ok.raw());
        assertEquals("ENGLISH9X", ok.body().path("grader").asText());
    }

    @Test
    void modeIsStoredAsTheOrgPolicy() {
        UUID center = org("CENTER");
        UUID admin = orgAdmin(center);
        su.update("insert into exams(code,name) values ('IELTS','IELTS') on conflict do nothing");

        assertEquals(200, setSettings(center, admin, "CENTER", "BOTH").status());
        assertEquals("BOTH", su.queryForObject(
                "select mode from grading_policies where owner_org_id = ? and scope = 'ORG' and exam_code = 'IELTS'", String.class, center));

        Res view = call(HttpMethod.GET, "/api/orgs/" + center + "/grading-settings", null, admin, center);
        assertEquals(200, view.status(), view.raw());
        assertEquals("BOTH", view.body().path("mode").asText());
    }
}
