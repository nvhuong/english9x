package com.english9x.content.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.content.question.QuestionIT;
import com.english9x.content.question.api.QuestionVersions;
import com.english9x.content.strategy.api.StrategyLessonVersions;
import com.english9x.workflow.api.UnpublishedDependencyException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** /api/cms/strategy-lessons (F14) over the real database, the real review workflow and real row-level security. */
class StrategyLessonIT extends QuestionIT {

    @Autowired
    StrategyLessonVersions lessons;
    @Autowired
    QuestionVersions questions;

    private ObjectNode block(String type) {
        return JSON.createObjectNode().put("type", type);
    }

    private ArrayNode blocks(JsonNode... bs) {
        ArrayNode a = JSON.createArrayNode();
        for (JsonNode b : bs) {
            a.add(b);
        }
        return a;
    }

    private Map<String, Object> body(Staff s, JsonNode blocks, List<String> types) {
        Map<String, Object> b = new HashMap<>();
        b.put("examCode", s.exam());
        b.put("title", "Matching headings: the method");
        b.put("blocks", blocks);
        b.put("questionTypeCodes", types);
        return b;
    }

    private List<String> fields(Res r) {
        List<String> out = new ArrayList<>();
        r.body().path("errors").forEach(e -> out.add(e.path("field").asText()));
        return out;
    }

    private ArrayNode everyBlock(Staff s, String questionVersionId) {
        UUID video = media(s.org(), s.exam(), "VIDEO", s.author());
        return blocks(
                block("heading").put("level", 2).put("text", "Read the headings first"),
                block("paragraph").put("text", "Skim each paragraph for its main idea."),
                block("table").<ObjectNode>set("header", JSON.createArrayNode().add("Step").add("Do")).set("rows", JSON.createArrayNode().add(JSON.createArrayNode().add("1").add("Skim"))),
                block("image").put("imageMediaId", s.image().toString()).put("alt", "A diagram"),
                block("audio").put("audioMediaId", s.audio().toString()),
                block("video-upload").put("videoMediaId", video.toString()),
                block("video-embed").put("provider", "youtube").put("url", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
                block("video-embed").put("provider", "vimeo").put("url", "https://vimeo.com/76979871"),
                block("callout").put("kind", "tip").put("text", "Underline the keywords."),
                block("callout").put("kind", "trap").put("text", "Do not match on a single word."),
                block("embedded-question").put("questionVersionId", questionVersionId),
                block("worked-example").put("title", "Example").put("problem", "Which heading fits?").<ObjectNode>set("steps", JSON.createArrayNode().add("Find the topic sentence.").add("Compare.")));
    }

    @Test
    void everyBlockTypeSavesAndLearnerReadsItAfterReview() {
        Staff s = staff("IELTS");
        Res q = createQuestion("IELTS_R_SHORT_ANSWER", s);
        submitAndApprove(q, s);
        String qv = q.body().path("versionId").asText();
        Res created = post("/api/cms/strategy-lessons", body(s, everyBlock(s, qv), List.of("IELTS_R_SHORT_ANSWER")), s.author(), s.org());
        assertEquals(201, created.status(), created.raw());
        assertEquals("DRAFT", created.body().path("status").asText());
        assertEquals(12, created.body().path("blocks").size());
        assertEquals(List.of("IELTS_R_SHORT_ANSWER"), List.of(created.body().path("questionTypeCodes").get(0).asText()));
        UUID version = UUID.fromString(created.body().path("versionId").asText());

        // a learner has no way in to a draft
        assertTrue(as(s.author(), s.org(), () -> lessons.examView(version)).isEmpty());

        String lessonId = created.body().path("lessonId").asText();
        UUID review = UUID.fromString(post("/api/cms/strategy-lessons/" + lessonId + "/submit", null, s.author(), s.org()).body().path("reviewId").asText());
        as(s.reviewer(), s.org(), () -> {
            reviews.approve(review);
            return null;
        });
        var view = as(s.author(), s.org(), () -> lessons.examView(version)).orElseThrow();
        assertEquals(12, view.blocks().size());
        // the embedded question is read through its learner view: content, never the answer key
        var embedded = as(s.author(), s.org(), () -> questions.examView(UUID.fromString(qv))).orElseThrow();
        assertFalse(JSON.valueToTree(embedded).toString().contains("slots"), "no answer key in the learner view of the embedded question");

        // editing the published lesson makes version 2 and never touches version 1
        Res v2 = put("/api/cms/strategy-lessons/" + lessonId, body(s, everyBlock(s, qv), List.of("IELTS_R_SHORT_ANSWER")), s.author(), s.org());
        assertEquals(201, v2.status(), v2.raw());
        assertEquals(2, v2.body().path("versionNo").asInt());
        assertEquals("PUBLISHED", su.queryForObject("select status from strategy_lesson_versions where id = ?", String.class, version));
        assertEquals(200, put("/api/cms/strategy-lessons/" + lessonId, body(s, everyBlock(s, qv), List.of("IELTS_R_SHORT_ANSWER")), s.author(), s.org()).status());
        assertEquals(1, get("/api/cms/strategy-lessons?exam=IELTS", s.author(), s.org()).body().path("items").size());
    }

    @Test
    void requiresAtLeastOneQuestionType() {
        Staff s = staff("IELTS");
        ArrayNode b = blocks(block("paragraph").put("text", "x"));
        Res none = post("/api/cms/strategy-lessons", body(s, b, List.of()), s.author(), s.org());
        assertEquals(422, none.status(), none.raw());
        assertEquals("urn:e9x:problem:strategy-lesson-invalid", none.problemType());
        assertEquals(List.of("questionTypeCodes"), fields(none));
        Res wrongExam = post("/api/cms/strategy-lessons", body(s, b, List.of("TOEIC_P5_INCOMPLETE_SENTENCE")), s.author(), s.org());
        assertEquals(422, wrongExam.status());
        assertEquals(List.of("questionTypeCodes[0]"), fields(wrongExam));
        assertEquals(0, (int) su.queryForObject("select count(*) from strategy_lessons where owner_org_id = ?", Integer.class, s.org()), "nothing was stored");
    }

    @Test
    void embeddedQuestionMustBePublishedBeforeLessonPublishes() {
        Staff s = staff("IELTS");
        Res q = createQuestion("IELTS_R_SHORT_ANSWER", s);
        String qv = q.body().path("versionId").asText();
        Res created = post("/api/cms/strategy-lessons", body(s, blocks(block("embedded-question").put("questionVersionId", qv)), List.of("IELTS_R_SHORT_ANSWER")), s.author(), s.org());
        assertEquals(201, created.status(), created.raw());
        String lessonId = created.body().path("lessonId").asText();
        UUID review = UUID.fromString(post("/api/cms/strategy-lessons/" + lessonId + "/submit", null, s.author(), s.org()).body().path("reviewId").asText());

        assertThrows(UnpublishedDependencyException.class, () -> as(s.reviewer(), s.org(), () -> {
            reviews.approve(review);
            return null;
        }));
        UUID lessonVersion = UUID.fromString(created.body().path("versionId").asText());
        assertEquals("IN_REVIEW", su.queryForObject("select status from strategy_lesson_versions where id = ?", String.class, lessonVersion));

        submitAndApprove(q, s);
        as(s.reviewer(), s.org(), () -> {
            reviews.approve(review);
            return null;
        });
        assertEquals("PUBLISHED", su.queryForObject("select status from strategy_lesson_versions where id = ?", String.class, lessonVersion));

        // a question that does not exist, or is of another exam, cannot be embedded at all
        Res missing = post("/api/cms/strategy-lessons", body(s, blocks(block("embedded-question").put("questionVersionId", UUID.randomUUID().toString())), List.of("IELTS_R_SHORT_ANSWER")), s.author(), s.org());
        assertEquals(422, missing.status());
        assertEquals(List.of("blocks[0].questionVersionId"), fields(missing));
    }

    @Test
    void rejectsNonAllowlistedVideoHost() {
        Staff s = staff("IELTS");
        for (String[] bad : new String[][] {
            {"youtube", "https://evil.example.com/watch?v=dQw4w9WgXcQ"},
            {"youtube", "http://www.youtube.com/watch?v=dQw4w9WgXcQ"},
            {"youtube", "https://www.youtube.com.evil.example/watch?v=dQw4w9WgXcQ"},
            {"vimeo", "https://dailymotion.com/video/76979871"},
            {"youtube", "https://user@www.youtube.com/watch?v=dQw4w9WgXcQ"},
            {"vimeo", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"},
            {"youtube", "javascript:alert(1)"}}) {
            Res r = post("/api/cms/strategy-lessons",
                    body(s, blocks(block("paragraph").put("text", "x"), block("video-embed").put("provider", bad[0]).put("url", bad[1])), List.of("IELTS_R_SHORT_ANSWER")),
                    s.author(), s.org());
            assertEquals(422, r.status(), bad[1] + ": " + r.raw());
            assertEquals(List.of("blocks[1].url"), fields(r), bad[1]);
        }
        Res ok = post("/api/cms/strategy-lessons",
                body(s, blocks(block("video-embed").put("provider", "youtube").put("url", "https://youtu.be/dQw4w9WgXcQ")), List.of("IELTS_R_SHORT_ANSWER")), s.author(), s.org());
        assertEquals(201, ok.status(), ok.raw());
    }

    @Test
    void shapeErrorsCarryJsonPathsAndOtherPeopleAreRefused() {
        Staff s = staff("IELTS");
        Res r = post("/api/cms/strategy-lessons",
                body(s, blocks(block("heading").put("level", 9).put("text", "h"), block("callout").put("kind", "joke").put("text", "t"), block("nope")), List.of("IELTS_R_SHORT_ANSWER")),
                s.author(), s.org());
        assertEquals(422, r.status());
        List<String> at = fields(r);
        assertTrue(at.contains("blocks[0].level") && at.contains("blocks[1].kind") && at.contains("blocks[2].type"), at.toString());
        // a file of another exam
        UUID other = media(s.org(), "TOEIC", "IMAGE", s.author());
        Res files = post("/api/cms/strategy-lessons",
                body(s, blocks(block("image").put("imageMediaId", other.toString()).put("alt", "a")), List.of("IELTS_R_SHORT_ANSWER")), s.author(), s.org());
        assertEquals(List.of("blocks[0].imageMediaId"), fields(files));

        // an author of another exam in the same organisation, and a reviewer who is no author
        UUID toeicAuthor = user("toeic-author");
        grant(toeicAuthor, s.org(), "TOEIC", "AUTHOR");
        Map<String, Object> simple = body(s, blocks(block("paragraph").put("text", "x")), List.of("IELTS_R_SHORT_ANSWER"));
        assertEquals(403, post("/api/cms/strategy-lessons", simple, toeicAuthor, s.org()).status());
        assertEquals(403, post("/api/cms/strategy-lessons", simple, s.reviewer(), s.org()).status());
        Res created = post("/api/cms/strategy-lessons", simple, s.author(), s.org());
        String id = created.body().path("lessonId").asText();
        assertEquals(404, get("/api/cms/strategy-lessons/" + id, toeicAuthor, s.org()).status());
        assertEquals(403, get("/api/cms/strategy-lessons?exam=IELTS", toeicAuthor, s.org()).status());
        UUID colleague = user("colleague");
        grant(colleague, s.org(), "IELTS", "AUTHOR");
        Res take = put("/api/cms/strategy-lessons/" + id, body(s, blocks(block("paragraph").put("text", "mine")), List.of("IELTS_R_SHORT_ANSWER")), colleague, s.org());
        assertEquals(409, take.status());
        assertEquals("urn:e9x:problem:draft-of-another-author", take.problemType());
    }
}
