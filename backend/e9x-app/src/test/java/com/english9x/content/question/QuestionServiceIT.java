package com.english9x.content.question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.content.question.api.QuestionVersionView;
import com.english9x.content.question.api.QuestionVersions;
import com.english9x.workflow.api.ContentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

/** POST / PUT / GET /api/cms/questions (F09, F18) over the real database, the real review workflow and real row-level security. */
class QuestionServiceIT extends QuestionIT {

    @Autowired
    QuestionTypes types;
    @Autowired
    QuestionVersions versions;

    private static List<String> fields(Res r) {
        List<String> out = new ArrayList<>();
        r.body().path("errors").forEach(e -> out.add(e.path("field").asText()));
        return out;
    }

    @Test
    void everyQuestionTypeOfTheCatalogHasAFormThatSaves() {
        List<QuestionTypeDef> all = types.all();
        assertTrue(all.size() >= 31, "the catalog lists " + all.size() + " types");
        Staff ielts = staff("IELTS");
        Staff toeic = staff("TOEIC");
        for (QuestionTypeDef def : all) {
            Staff s = def.examCode().equals("IELTS") ? ielts : toeic;
            Res r = createQuestion(def.code(), s);
            assertEquals(201, r.status(), def.code() + ": " + r.raw());
            assertEquals("DRAFT", r.body().path("status").asText(), def.code());
            assertEquals(1, r.body().path("versionNo").asInt(), def.code());
            assertEquals(def.examCode(), r.body().path("examCode").asText(), def.code());
            assertEquals(s.author().toString(), r.body().path("createdBy").asText(), "the author is the session's user: " + def.code());
            assertEquals(s.org().toString(), r.body().path("ownerOrgId").asText(), def.code());
            assertEquals(example(def.code(), s).get("answerKey"), r.body().path("answerKey"), "the author reads their own key back: " + def.code());
        }
        assertEquals(all.size(), su.queryForObject("select count(*) from questions where owner_org_id in (?,?)", Integer.class, ielts.org(), toeic.org()));
    }

    @Test
    void invalidContentRejectedWithJsonPaths() {
        Staff s = staff("TOEIC");
        ObjectNode ex = example("TOEIC_P3_CONVERSATION", s);
        ((ObjectNode) ex.at("/content/options/2")).put("key", "TOOLONG");
        ((ObjectNode) ex.get("content")).remove("stem");
        ((ObjectNode) ex.get("answerKey")).putArray("slots").addArray().add("SECRET-ANSWER-9").add("B");
        int before = su.queryForObject("select count(*) from questions where owner_org_id = ?", Integer.class, s.org());

        Res r = post("/api/cms/questions", body("TOEIC_P3_CONVERSATION", ex), s.author(), s.org());

        assertEquals(422, r.status(), r.raw());
        assertEquals("urn:e9x:problem:question-invalid", r.problemType());
        List<String> at = fields(r);
        assertTrue(at.contains("content.options[2].key"), "the path of the bad option key: " + at);
        assertTrue(at.contains("content.stem"), "a missing field is named by its path: " + at);
        assertTrue(at.stream().anyMatch(f -> f.startsWith("answerKey.slots")), "the answer key is checked too: " + at);
        for (JsonNode e : r.body().path("errors")) {
            if (e.path("field").asText().equals("content.options[2].key")) {
                assertEquals(2, e.path("row").asInt(), "an error inside a list carries the index of the element");
            }
        }
        assertFalse(r.raw().contains("SECRET-ANSWER-9"), "no message repeats an answer: " + r.raw());
        assertEquals(before, (int) su.queryForObject("select count(*) from questions where owner_org_id = ?", Integer.class, s.org()), "nothing was stored");

        // a file that is not the exam's, not an audio file, and a type nobody knows
        UUID other = media(s.org(), "IELTS", "AUDIO", s.author());
        ObjectNode bad = example("TOEIC_P3_CONVERSATION", s);
        ((ObjectNode) bad.get("content")).put("audioMediaId", other.toString());
        ((ObjectNode) bad.get("content")).put("imageMediaId", s.audio().toString());
        Res files = post("/api/cms/questions", body("TOEIC_P3_CONVERSATION", bad), s.author(), s.org());
        assertEquals(422, files.status(), files.raw());
        assertTrue(fields(files).containsAll(List.of("content.audioMediaId", "content.imageMediaId")), fields(files).toString());
        Res unknown = post("/api/cms/questions", body("NOPE", example("TOEIC_P3_CONVERSATION", s)), s.author(), s.org());
        assertEquals(422, unknown.status());
        assertEquals(List.of("typeCode"), fields(unknown));
        Map<String, Object> noPoints = body("TOEIC_P3_CONVERSATION", example("TOEIC_P3_CONVERSATION", s));
        noPoints.put("points", 0);
        assertEquals(List.of("points"), fields(post("/api/cms/questions", noPoints, s.author(), s.org())));
    }

    @Test
    void editingPublishedCreatesNewDraftVersion() {
        Staff s = staff("IELTS");
        Res created = createQuestion("IELTS_R_SHORT_ANSWER", s);
        UUID questionId = submitAndApproveFirst(created, s);
        UUID v1 = UUID.fromString(created.body().path("versionId").asText());
        assertEquals("PUBLISHED", su.queryForObject("select status from question_versions where id = ?", String.class, v1));
        JsonNode oldContent = created.body().path("content");

        ObjectNode changed = example("IELTS_R_SHORT_ANSWER", s);
        ((ObjectNode) changed.get("content")).put("stem", "A different question?");
        Res edited = put("/api/cms/questions/" + questionId, body("IELTS_R_SHORT_ANSWER", changed), s.author(), s.org());
        assertEquals(201, edited.status(), "a new version: " + edited.raw());
        assertEquals(2, edited.body().path("versionNo").asInt());
        assertEquals("DRAFT", edited.body().path("status").asText());
        UUID v2 = UUID.fromString(edited.body().path("versionId").asText());
        assertTrue(!v1.equals(v2));

        // the published version is untouched; the learner-facing view still serves it
        assertEquals("PUBLISHED", su.queryForObject("select status from question_versions where id = ?", String.class, v1));
        assertEquals(oldContent, JSON.valueToTree(as(s.author(), s.org(), () -> versions.find(v1).orElseThrow().content())), "version 1 keeps its content");
        assertEquals(oldContent, as(s.author(), s.org(), () -> versions.examView(v1).orElseThrow().content()));
        assertTrue(as(s.author(), s.org(), () -> versions.examView(v2)).isEmpty(), "the draft has no learner view");

        // a second edit changes the open draft in place (same version, 200)
        ((ObjectNode) changed.get("content")).put("stem", "And again?");
        Res again = put("/api/cms/questions/" + questionId, body("IELTS_R_SHORT_ANSWER", changed), s.author(), s.org());
        assertEquals(200, again.status(), again.raw());
        assertEquals(v2.toString(), again.body().path("versionId").asText());
        assertEquals("And again?", again.body().path("content").path("stem").asText());
        assertEquals(2, (int) su.queryForObject("select count(*) from question_versions where question_id = ?", Integer.class, questionId));

        // the list shows the latest version of each question; the versions route shows all of them with their keys
        Res list = get("/api/cms/questions?exam=IELTS", s.author(), s.org());
        assertEquals(200, list.status(), list.raw());
        assertEquals(1, list.body().path("total").asInt());
        assertEquals(2, list.body().path("items").get(0).path("versionNo").asInt());
        assertEquals("DRAFT", list.body().path("items").get(0).path("status").asText());
        assertEquals(1, get("/api/cms/questions?exam=IELTS&status=DRAFT", s.author(), s.org()).body().path("items").size());
        assertEquals(0, get("/api/cms/questions?exam=IELTS&status=PUBLISHED", s.author(), s.org()).body().path("items").size(), "the filter is on the latest version");
        assertEquals(0, get("/api/cms/questions?exam=IELTS&type=IELTS_R_MULTIPLE_CHOICE", s.author(), s.org()).body().path("items").size());
        Res all = get("/api/cms/questions/" + questionId + "/versions", s.reviewer(), s.org());
        assertEquals(200, all.status(), all.raw());
        assertEquals(List.of("PUBLISHED", "DRAFT"), List.of(all.body().get(0).path("status").asText(), all.body().get(1).path("status").asText()));
        assertEquals(1, get("/api/cms/questions/" + questionId + "?version=1", s.author(), s.org()).body().path("versionNo").asInt());
    }

    private UUID submitAndApproveFirst(Res created, Staff s) {
        return submitAndApprove(created, s);
    }

    @Test
    void authorOfOtherExamForbidden() {
        Staff ielts = staff("IELTS");
        UUID toeicAuthor = user("toeic-author");
        grant(toeicAuthor, ielts.org(), "TOEIC", "AUTHOR");
        Res q = createQuestion("IELTS_R_SHORT_ANSWER", ielts);
        String id = q.body().path("questionId").asText();

        Res create = post("/api/cms/questions", body("IELTS_R_SHORT_ANSWER", example("IELTS_R_SHORT_ANSWER", ielts)), toeicAuthor, ielts.org());
        assertEquals(403, create.status(), create.raw());
        assertEquals(403, get("/api/cms/questions?exam=IELTS", toeicAuthor, ielts.org()).status());
        // the question is invisible to them: 404, the same as for an id nobody has
        assertEquals(404, get("/api/cms/questions/" + id, toeicAuthor, ielts.org()).status());
        assertEquals(404, put("/api/cms/questions/" + id, body("IELTS_R_SHORT_ANSWER", example("IELTS_R_SHORT_ANSWER", ielts)), toeicAuthor, ielts.org()).status());
        assertEquals(404, post("/api/cms/questions/" + id + "/submit", null, toeicAuthor, ielts.org()).status());
        assertEquals(get("/api/cms/questions/" + UUID.randomUUID(), toeicAuthor, ielts.org()).problemType(), get("/api/cms/questions/" + id, toeicAuthor, ielts.org()).problemType());
        assertEquals(0, get("/api/cms/questions", toeicAuthor, ielts.org()).body().path("items").size(), "and their list holds none of it");

        // a learner, a teacher and an org admin of the same organisation
        UUID learner = user("learner");
        grant(learner, ielts.org(), null, "LEARNER");
        UUID teacher = user("teacher");
        grant(teacher, ielts.org(), "IELTS", "TEACHER");
        UUID admin = orgAdmin(ielts.org());
        for (UUID who : List.of(learner, teacher, admin)) {
            assertEquals(403, post("/api/cms/questions", body("IELTS_R_SHORT_ANSWER", example("IELTS_R_SHORT_ANSWER", ielts)), who, ielts.org()).status());
            assertEquals(404, get("/api/cms/questions/" + id, who, ielts.org()).status());
            assertEquals(403, get("/api/cms/questions", who, ielts.org()).status(), "no list without a role");
        }
        assertEquals(401, get("/api/cms/questions", null, null).status());
    }

    @Test
    void anotherOrganisationAndAnotherContextSeeNothing() {
        Staff a = staff("IELTS");
        Staff b = staff("IELTS");
        String id = createQuestion("IELTS_R_SHORT_ANSWER", a).body().path("questionId").asText();
        assertEquals(404, get("/api/cms/questions/" + id, b.author(), b.org()).status(), "another organisation's author");
        assertEquals(0, get("/api/cms/questions?exam=IELTS", b.author(), b.org()).body().path("items").size());
        member(b.org(), a.author());
        assertEquals(404, get("/api/cms/questions/" + id, a.author(), b.org()).status(), "the same person in the other organisation's context");
        assertEquals(404, get("/api/cms/questions/" + id, a.author(), null).status(), "and in the personal context");
        // English9x's author: a question of English9x is created in the personal context, with the flag derived from the grant
        UUID e9 = user("e9");
        grant(e9, null, "IELTS", "AUTHOR");
        Res mine = post("/api/cms/questions", body("IELTS_R_SHORT_ANSWER", example("IELTS_R_SHORT_ANSWER", withoutMedia(a))), e9, null);
        assertEquals(201, mine.status(), mine.raw());
        assertTrue(mine.body().path("ownerOrgId").isNull(), "English9x content has no owner");
        assertEquals(404, get("/api/cms/questions/" + mine.body().path("questionId").asText(), a.author(), a.org()).status(), "an organisation does not read English9x drafts");
    }

    private Staff withoutMedia(Staff a) {
        return new Staff(a.org(), a.author(), a.reviewer(), a.exam(), null, null);
    }

    @Test
    void aColleagueCannotEditOrSubmitAnAuthorsDraftAndAnAuthorCannotApproveTheirOwn() {
        Staff s = staff("IELTS");
        UUID colleague = user("colleague");
        grant(colleague, s.org(), "IELTS", "AUTHOR");
        Res created = createQuestion("IELTS_R_SHORT_ANSWER", s);
        String id = created.body().path("questionId").asText();
        // the colleague reads it (an author of the exam) but changes nothing and submits nothing: the only contributor is the creator
        assertEquals(200, get("/api/cms/questions/" + id, colleague, s.org()).status());
        Res edit = put("/api/cms/questions/" + id, body("IELTS_R_SHORT_ANSWER", example("IELTS_R_SHORT_ANSWER", s)), colleague, s.org());
        assertEquals(409, edit.status(), edit.raw());
        assertEquals("urn:e9x:problem:draft-of-another-author", edit.problemType());
        assertEquals(409, post("/api/cms/questions/" + id + "/submit", null, colleague, s.org()).status());

        // a person who is both AUTHOR and REVIEWER of the exam cannot approve what they wrote (the review trigger compares with the creator)
        UUID both = user("both");
        grant(both, s.org(), "IELTS", "AUTHOR");
        grant(both, s.org(), "IELTS", "REVIEWER");
        Res own = post("/api/cms/questions", body("IELTS_R_SHORT_ANSWER", example("IELTS_R_SHORT_ANSWER", s)), both, s.org());
        assertEquals(201, own.status(), own.raw());
        Res submitted = post("/api/cms/questions/" + own.body().path("questionId").asText() + "/submit", null, both, s.org());
        assertEquals(202, submitted.status(), submitted.raw());
        UUID review = UUID.fromString(submitted.body().path("reviewId").asText());
        assertThrows(AccessDeniedException.class, () -> as(both, s.org(), () -> {
            reviews.approve(review);
            return null;
        }));
        // while someone who only reviews can
        as(s.reviewer(), s.org(), () -> {
            reviews.approve(review);
            return null;
        });
        assertEquals("PUBLISHED", su.queryForObject("select status from question_versions where id = ?", String.class, UUID.fromString(own.body().path("versionId").asText())));
    }

    @Test
    void aVersionUnderReviewCannotBeEditedAndTheReviewerSeesTheKey() {
        Staff s = staff("IELTS");
        Res created = createQuestion("IELTS_R_SHORT_ANSWER", s);
        String id = created.body().path("questionId").asText();
        assertEquals(202, post("/api/cms/questions/" + id + "/submit", null, s.author(), s.org()).status());
        Res edit = put("/api/cms/questions/" + id, body("IELTS_R_SHORT_ANSWER", example("IELTS_R_SHORT_ANSWER", s)), s.author(), s.org());
        assertEquals(409, edit.status(), edit.raw());
        assertEquals("urn:e9x:problem:version-under-review", edit.problemType());
        assertEquals(409, post("/api/cms/questions/" + id + "/submit", null, s.author(), s.org()).status(), "nothing left to submit");
        Res asReviewer = get("/api/cms/questions/" + id, s.reviewer(), s.org());
        assertEquals(200, asReviewer.status());
        assertEquals(created.body().path("answerKey"), asReviewer.body().path("answerKey"), "the reviewer reads the key under review");
        assertEquals("no-store", asReviewer.headers().getCacheControl());
        assertEquals(400, put("/api/cms/questions/not-a-uuid", body("IELTS_R_SHORT_ANSWER", example("IELTS_R_SHORT_ANSWER", s)), s.author(), s.org()).status());
        // the type is fixed for the life of a question
        Res retype = put("/api/cms/questions/" + id, body("IELTS_R_MULTIPLE_CHOICE", example("IELTS_R_SHORT_ANSWER", s)), s.author(), s.org());
        assertTrue(retype.status() == 409 || retype.status() == 422, retype.raw());
    }

    @Test
    void theReviewerCanRequestChangesAndTheAuthorEditsTheSameVersionAgain() {
        Staff s = staff("IELTS");
        Res created = createQuestion("IELTS_R_SHORT_ANSWER", s);
        String id = created.body().path("questionId").asText();
        UUID review = UUID.fromString(post("/api/cms/questions/" + id + "/submit", null, s.author(), s.org()).body().path("reviewId").asText());
        as(s.reviewer(), s.org(), () -> {
            reviews.requestChanges(review, "please fix the stem");
            return null;
        });
        ObjectNode changed = example("IELTS_R_SHORT_ANSWER", s);
        ((ObjectNode) changed.get("content")).put("stem", "Fixed?");
        Res again = put("/api/cms/questions/" + id, body("IELTS_R_SHORT_ANSWER", changed), s.author(), s.org());
        assertEquals(200, again.status(), again.raw());
        assertEquals(created.body().path("versionId").asText(), again.body().path("versionId").asText(), "the same version, back in the author's hands");
        assertEquals("CHANGES_REQUESTED", again.body().path("status").asText());
        assertEquals(202, post("/api/cms/questions/" + id + "/submit", null, s.author(), s.org()).status(), "and submitted again");
    }

    @Test
    void examViewIsTheContentSideOnly() {
        Staff s = staff("TOEIC");
        Res created = createQuestion("TOEIC_P3_CONVERSATION", s);
        submitAndApprove(created, s);
        UUID versionId = UUID.fromString(created.body().path("versionId").asText());
        UUID learner = user("learner");
        grant(learner, s.org(), null, "LEARNER");
        // the learner's side: the content of the published version, found through the function, nothing else; the staff side finds nothing for them
        var view = as(learner, s.org(), () -> versions.examView(versionId)).orElseThrow();
        assertEquals(created.body().path("content"), view.content());
        assertTrue(as(learner, s.org(), () -> versions.find(versionId)).isEmpty(), "a learner reads no staff view");
        assertTrue(as(learner, null, () -> versions.examView(versionId)).isEmpty(), "and none from outside the owner's context");
        QuestionVersionView staff = as(s.author(), s.org(), () -> versions.find(versionId)).orElseThrow();
        assertEquals(ContentStatus.PUBLISHED, staff.status());
        assertEquals(1, as(s.reviewer(), s.org(), () -> versions.versions(staff.questionId())).size());
    }

    @Test
    void theEditorGetsTheSchemasOfTheTypesOfItsExamOnly() {
        Staff s = staff("TOEIC");
        Res r = get("/api/cms/question-types", s.author(), s.org());
        assertEquals(200, r.status(), r.raw());
        assertEquals(types.forExam("TOEIC").size(), r.body().size(), "only the types of the exam the person writes");
        assertEquals("TOEIC", r.body().get(0).path("examCode").asText());
        assertTrue(r.body().get(0).path("contentSchema").has("properties") && r.body().get(0).path("answerKeySchema").has("properties"));
        assertEquals(200, get("/api/cms/question-types?exam=TOEIC", s.reviewer(), s.org()).status());
        UUID learner = user("learner");
        grant(learner, s.org(), null, "LEARNER");
        assertEquals(403, get("/api/cms/question-types", learner, s.org()).status(), "the answer-key schema names the solution side: not for a learner");
        assertEquals(401, get("/api/cms/question-types", null, null).status());
    }
}
