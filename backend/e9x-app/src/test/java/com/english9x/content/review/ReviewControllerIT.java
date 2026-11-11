package com.english9x.content.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.content.question.QuestionIT;
import com.english9x.workflow.api.BundleRef;
import com.english9x.workflow.api.VersionRef;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The review screens' API (F17, F18) over the real workflow: queue, detail with the diff base, decisions, self-review and who sees the author's name. */
class ReviewControllerIT extends QuestionIT {

    private static final String TYPE = "IELTS_R_SHORT_ANSWER";

    private UUID submit(Res created, Staff s) {
        UUID q = UUID.fromString(created.body().path("questionId").asText());
        Res r = post("/api/cms/questions/" + q + "/submit", null, s.author(), s.org());
        assertEquals(202, r.status(), r.raw());
        return UUID.fromString(r.body().path("reviewId").asText());
    }

    private int audited(String action, UUID id) {
        return su.queryForObject("select count(*) from audit_log where action = ? and target_id = ?", Integer.class, action, id.toString());
    }

    @Test
    void queueDetailAndApproveWithTheAuthorsNameAndTheEarlierVersion() {
        Staff s = staff("IELTS");
        Res created = createQuestion(TYPE, s);
        UUID review = submit(created, s);

        Res queue = get("/api/cms/reviews", s.reviewer(), s.org());
        assertEquals(200, queue.status());
        assertEquals(1, queue.body().size());
        assertEquals(review.toString(), queue.body().get(0).path("reviewId").asText());
        assertTrue(queue.body().get(0).path("authorName").asText().startsWith("User author-"), "the reviewer sees the author's name through the narrow definer");

        Res detail = get("/api/cms/reviews/" + review, s.reviewer(), s.org());
        assertEquals(200, detail.status(), detail.raw());
        assertTrue(detail.body().path("authorName").asText().startsWith("User author-"));
        assertFalse(detail.body().path("ownVersion").asBoolean());
        assertEquals(1, detail.body().path("version").path("versionNo").asInt());
        assertTrue(detail.body().path("version").path("content").has("content"), "the content, without identity fields");
        assertFalse(detail.body().path("version").path("content").has("createdBy"));
        assertTrue(detail.body().path("previous").isNull() || detail.body().path("previous").isMissingNode());

        assertEquals(204, post("/api/cms/reviews/" + review + "/approve", null, s.reviewer(), s.org()).status());
        assertEquals(1, audited("review.approve", review), "audited once, by the workflow, not again by the controller");
        assertEquals(409, post("/api/cms/reviews/" + review + "/approve", null, s.reviewer(), s.org()).status(), "decided once");

        // version 2: the detail carries version 1 to compare with
        UUID questionId = UUID.fromString(created.body().path("questionId").asText());
        ObjectNode ex = example(TYPE, s);
        ((ObjectNode) ex.get("content")).put("stem", "A changed stem?");
        assertEquals(201, put("/api/cms/questions/" + questionId, body(TYPE, ex), s.author(), s.org()).status());
        UUID review2 = UUID.fromString(post("/api/cms/questions/" + questionId + "/submit", null, s.author(), s.org()).body().path("reviewId").asText());
        Res d2 = get("/api/cms/reviews/" + review2, s.reviewer(), s.org());
        assertEquals(2, d2.body().path("version").path("versionNo").asInt());
        assertEquals(1, d2.body().path("previous").path("versionNo").asInt());
        assertEquals("A changed stem?", d2.body().path("version").path("content").path("content").path("stem").asText());
        assertEquals(ex.path("content").path("stem").asText().equals("A changed stem?"), true);
        assertFalse(d2.body().path("previous").path("content").path("content").path("stem").asText().equals("A changed stem?"));
    }

    @Test
    void requestChangesNeedsACommentAndReturnsTheVersion() {
        Staff s = staff("IELTS");
        UUID review = submit(createQuestion(TYPE, s), s);
        assertEquals(400, post("/api/cms/reviews/" + review + "/request-changes", Map.of("comment", "  "), s.reviewer(), s.org()).status());
        assertEquals(204, post("/api/cms/reviews/" + review + "/request-changes", Map.of("comment", "Fix the key"), s.reviewer(), s.org()).status());
        assertEquals(1, audited("review.request-changes", review));
        assertEquals("Fix the key", get("/api/cms/reviews/" + review, s.author(), s.org()).body().path("review").path("comment").asText());
        assertEquals("CHANGES_REQUESTED", su.queryForObject("select status from review_requests where id = ?", String.class, review));
    }

    @Test
    void aReviewerCannotApproveOrSendBackTheVersionTheyWroteAndOnlyTheReviewerSeesTheName() {
        Staff s = staff("IELTS");
        UUID both = user("both");
        grant(both, s.org(), "IELTS", "AUTHOR");
        grant(both, s.org(), "IELTS", "REVIEWER");
        Res created = post("/api/cms/questions", body(TYPE, example(TYPE, s)), both, s.org());
        assertEquals(201, created.status(), created.raw());
        UUID q = UUID.fromString(created.body().path("questionId").asText());
        UUID review = UUID.fromString(post("/api/cms/questions/" + q + "/submit", null, both, s.org()).body().path("reviewId").asText());

        assertTrue(get("/api/cms/reviews/" + review, both, s.org()).body().path("ownVersion").asBoolean());
        assertEquals(403, post("/api/cms/reviews/" + review + "/approve", null, both, s.org()).status());
        assertEquals(403, post("/api/cms/reviews/" + review + "/request-changes", Map.of("comment", "x"), both, s.org()).status());
        assertEquals("IN_REVIEW", su.queryForObject("select status from question_versions where question_id = ?", String.class, q));
        // the plain author of another exam's reviewer is no reviewer: the colleague reviewer decides
        assertEquals(204, post("/api/cms/reviews/" + review + "/approve", null, s.reviewer(), s.org()).status());
    }

    @Test
    void anotherOrganisationAndAnAuthorWithoutAReviewerGrantGetNothingToDecide() {
        Staff a = staff("IELTS");
        Staff b = staff("IELTS");
        UUID review = submit(createQuestion(TYPE, a), a);
        assertEquals(404, get("/api/cms/reviews/" + review, b.reviewer(), b.org()).status());
        assertEquals(404, post("/api/cms/reviews/" + review + "/approve", null, b.reviewer(), b.org()).status());
        assertEquals(0, get("/api/cms/reviews", b.reviewer(), b.org()).body().size());
        assertEquals(403, post("/api/cms/reviews/" + review + "/approve", null, a.author(), a.org()).status());
        assertEquals(1, get("/api/cms/reviews", a.author(), a.org()).body().size(), "the author sees their own pending submission");
        assertTrue(get("/api/cms/reviews", a.author(), a.org()).body().get(0).path("authorName").isNull(), "and gets no name from the reviewer-only definer");
    }

    @Test
    void aBundleIsReadAndDecidedAsAWhole() {
        Staff s = staff("IELTS");
        UUID v1 = UUID.fromString(createQuestion(TYPE, s).body().path("versionId").asText());
        UUID v2 = UUID.fromString(createQuestion(TYPE, s).body().path("versionId").asText());
        UUID bundle = as(s.author(), s.org(), () -> reviews.submit(new BundleRef(List.of(new VersionRef("question", v1), new VersionRef("question", v2)))));

        Res queue = get("/api/cms/reviews", s.reviewer(), s.org());
        assertEquals(1, queue.body().size());
        assertEquals(bundle.toString(), queue.body().get(0).path("bundleId").asText());
        assertEquals(2, queue.body().get(0).path("size").asInt());
        assertTrue(queue.body().get(0).path("authorName").asText().startsWith("User author-"));
        Res members = get("/api/cms/reviews/bundles/" + bundle + "/members", s.reviewer(), s.org());
        assertEquals(2, members.body().size());
        assertTrue(members.body().get(0).path("authorName").asText().startsWith("User author-"));
        assertEquals(403, get("/api/cms/reviews/bundles/" + bundle + "/members", user("stranger"), s.org()).status(), "a person outside the organisation is refused");

        assertEquals(204, post("/api/cms/reviews/bundles/" + bundle + "/approve", null, s.reviewer(), s.org()).status());
        assertEquals(1, audited("review.approve-bundle", bundle));
        assertEquals(2, su.queryForObject("select count(*) from question_versions where id in (?,?) and status = 'PUBLISHED'", Integer.class, v1, v2));
    }
}
