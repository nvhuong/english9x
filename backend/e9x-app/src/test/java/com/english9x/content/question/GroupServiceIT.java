package com.english9x.content.question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.content.question.api.QuestionGroupVersions;
import com.english9x.workflow.api.UnpublishedDependencyException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** /api/cms/question-groups: the shared stimulus and the ordered member question versions, with the workflow dependency on the members. */
class GroupServiceIT extends QuestionIT {

    @Autowired
    QuestionGroupVersions groupVersions;

    private Map<String, Object> groupBody(Staff s, List<String> members) {
        ObjectNode content = JSON.createObjectNode().put("title", "Passage 1").put("passage", "Some text.").put("audioMediaId", s.audio().toString());
        ObjectNode solution = JSON.createObjectNode().put("transcript", "SECRET-TRANSCRIPT");
        return Map.of("examCode", s.exam(), "content", content, "solution", solution, "questionVersionIds", members);
    }

    private String question(Staff s) {
        return createQuestion("IELTS_R_SHORT_ANSWER", s).body().path("versionId").asText();
    }

    @Test
    void groupCannotBeApprovedUntilEveryMemberIsPublishedAndThenKeepsItsVersions() {
        Staff s = staff("IELTS");
        Res q1 = createQuestion("IELTS_R_SHORT_ANSWER", s);
        Res q2 = createQuestion("IELTS_R_SHORT_ANSWER", s);
        List<String> members = List.of(q1.body().path("versionId").asText(), q2.body().path("versionId").asText());
        Res created = post("/api/cms/question-groups", groupBody(s, members), s.author(), s.org());
        assertEquals(201, created.status(), created.raw());
        assertEquals(members, List.of(created.body().path("questionVersionIds").get(0).asText(), created.body().path("questionVersionIds").get(1).asText()));
        String groupId = created.body().path("groupId").asText();

        UUID review = UUID.fromString(post("/api/cms/question-groups/" + groupId + "/submit", null, s.author(), s.org()).body().path("reviewId").asText());
        // the members are drafts: the workflow refuses to publish the group
        assertThrows(UnpublishedDependencyException.class, () -> as(s.reviewer(), s.org(), () -> {
            reviews.approve(review);
            return null;
        }));
        assertEquals("IN_REVIEW", su.queryForObject("select status from question_group_versions where id = ?", String.class, UUID.fromString(created.body().path("versionId").asText())));

        submitAndApprove(q1, s);
        submitAndApprove(q2, s);
        as(s.reviewer(), s.org(), () -> {
            reviews.approve(review);
            return null;
        });
        UUID v1 = UUID.fromString(created.body().path("versionId").asText());
        var learnerView = as(s.author(), s.org(), () -> groupVersions.examView(v1)).orElseThrow();
        assertEquals(List.of(UUID.fromString(members.get(0)), UUID.fromString(members.get(1))), learnerView.questionVersionIds());
        assertEquals("Passage 1", learnerView.content().path("title").asText());
        assertTrue(!learnerView.toString().contains("SECRET") && !JSON.valueToTree(learnerView).toString().contains("transcript"), "the learner view has no solution side");
        assertEquals("SECRET-TRANSCRIPT", as(s.author(), s.org(), () -> groupVersions.find(v1)).orElseThrow().solution().path("transcript").asText());

        // editing the published group makes version 2 (with a different member list); version 1 keeps its members
        Res q3 = createQuestion("IELTS_R_SHORT_ANSWER", s);
        Res v2 = put("/api/cms/question-groups/" + groupId, groupBody(s, List.of(q3.body().path("versionId").asText())), s.author(), s.org());
        assertEquals(201, v2.status(), v2.raw());
        assertEquals(2, v2.body().path("versionNo").asInt());
        assertEquals(2, (int) su.queryForObject("select count(*) from question_group_items where group_version_id = ?", Integer.class, v1), "the published version keeps both members");
        Res again = put("/api/cms/question-groups/" + groupId, groupBody(s, members), s.author(), s.org());
        assertEquals(200, again.status(), "the open draft is edited in place: " + again.raw());
        assertEquals(2, again.body().path("questionVersionIds").size());
        assertEquals(2, get("/api/cms/question-groups/" + groupId + "/versions", s.author(), s.org()).body().size());
        assertEquals(1, get("/api/cms/question-groups?exam=IELTS", s.author(), s.org()).body().path("items").size());
    }

    @Test
    void invalidGroupsAreRefusedByPath() {
        Staff s = staff("IELTS");
        Staff other = staff("IELTS");
        String foreign = question(other);
        Map<String, Object> bad = Map.of("examCode", "IELTS", "content", JSON.createObjectNode().put("passage", "x").put("surprise", "y"),
                "questionVersionIds", List.of(question(s), foreign, question(s).toString()));
        Res r = post("/api/cms/question-groups", bad, s.author(), s.org());
        assertEquals(422, r.status(), r.raw());
        List<String> at = new ArrayList<>();
        r.body().path("errors").forEach(e -> at.add(e.path("field").asText()));
        assertTrue(at.contains("content.title") && at.contains("content.surprise") && at.contains("questionVersionIds[1]"), at.toString());
        assertEquals(422, post("/api/cms/question-groups", Map.of("examCode", "IELTS", "content", JSON.createObjectNode().put("title", "t"), "questionVersionIds", List.of()),
                s.author(), s.org()).status(), "a group needs a member");
        // a draft group of another author is not edited by a colleague; another exam's author may not create
        UUID toeic = user("toeic");
        grant(toeic, s.org(), "TOEIC", "AUTHOR");
        assertEquals(403, post("/api/cms/question-groups", groupBody(s, List.of(question(s))), toeic, s.org()).status());
        Res ok = post("/api/cms/question-groups", groupBody(s, List.of(question(s))), s.author(), s.org());
        assertEquals(201, ok.status(), ok.raw());
        assertEquals(404, get("/api/cms/question-groups/" + ok.body().path("groupId").asText(), toeic, s.org()).status());
        assertEquals(404, get("/api/cms/question-groups/" + ok.body().path("groupId").asText(), other.author(), other.org()).status());
        UUID colleague = user("colleague");
        grant(colleague, s.org(), "IELTS", "AUTHOR");
        assertEquals(409, put("/api/cms/question-groups/" + ok.body().path("groupId").asText(), groupBody(s, List.of(question(s))), colleague, s.org()).status());
    }

    @Test
    void membersOfAGroupVersionUnderReviewAreFrozenInTheDatabase() {
        Staff s = staff("IELTS");
        Res g = post("/api/cms/question-groups", groupBody(s, List.of(question(s))), s.author(), s.org());
        UUID version = UUID.fromString(g.body().path("versionId").asText());
        assertEquals(202, post("/api/cms/question-groups/" + g.body().path("groupId").asText() + "/submit", null, s.author(), s.org()).status());
        for (String sql : List.of("delete from question_group_items where group_version_id = ?",
                "update question_group_items set position = 2 where group_version_id = ?")) {
            var ex = assertThrows(org.springframework.dao.DataAccessException.class, () -> su.update(sql, version), sql);
            assertTrue(String.valueOf(ex.getMessage()).contains("cannot change"), ex.getMessage());
        }
        assertThrows(org.springframework.dao.DataAccessException.class, () -> su.update(
                "insert into question_group_items(group_version_id,position,question_version_id,owner_org_id,exam_code) select ?, 2, id, owner_org_id, exam_code from question_versions where owner_org_id = ? limit 1",
                version, s.org()));
    }
}
