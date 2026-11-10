package com.english9x.content.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** /api/cms/tests: who may build a test, what a structure may hold (checked by JSON path), what only warns, what blocks a review, and the versions. */
class TestServiceIT extends TestIT {

    private static List<String> fields(Res r) {
        List<String> at = new ArrayList<>();
        r.body().path("errors").forEach(e -> at.add(e.path("field").asText()));
        return at;
    }

    @Test
    void anAuthorBuildsAToeicTestAndTheCmsSeesWhatTheExamExpects() {
        Staff s = staff("TOEIC");
        Built built = buildToeicTest(s, "Mock test");
        JsonNodeView v = new JsonNodeView(built.test());
        assertEquals("DRAFT", v.text("status"));
        assertEquals(1, v.int_("versionNo"));
        assertEquals(2, built.test().body().path("parts").size());
        assertEquals("Group of TOEIC_P1_PHOTO", built.test().body().path("parts").get(0).path("groups").get(0).path("title").asText());
        // seven sections in the report, one line each; counts that differ are warnings, not errors
        assertEquals(7, built.test().body().path("sections").size());
        assertEquals(7, built.test().body().path("warnings").size(), "P1 and P5 are short, the other five parts are missing: " + built.test().raw());
        assertEquals("SHORT", built.test().body().path("sections").get(0).path("state").asText(), "P1 has 1 of 6 questions");
        assertEquals(0, built.test().body().path("blockers").size(), "both skills are there");
        String testId = built.test().body().path("testId").asText();
        // listed, read, versions
        assertEquals(1, get("/api/cms/tests?exam=TOEIC", s.author(), s.org()).body().path("items").size());
        assertEquals(200, get("/api/cms/tests/" + testId, s.reviewer(), s.org()).status(), "a reviewer of the exam reads it");
        assertEquals(1, get("/api/cms/tests/" + testId + "/versions", s.author(), s.org()).body().size());
        // the draft is edited in place
        Res again = put("/api/cms/tests/" + testId + "/structure", Map.of("title", "Renamed", "maxResumes", 5, "parts",
                List.of(part("P1", 700, built.listeningGroup()), part("P5", 1200, built.readingGroup()))), s.author(), s.org());
        assertEquals(200, again.status(), again.raw());
        assertEquals("Renamed", again.body().path("title").asText());
        assertEquals(5, again.body().path("maxResumes").asInt());
        assertEquals(700, again.body().path("parts").get(0).path("durationSec").asInt());
    }

    @Test
    void createIsCheckedByPathAndByRole() {
        Staff s = staff("IELTS");
        Res noTitle = createTest(s, "AC", " ");
        assertEquals(422, noTitle.status(), noTitle.raw());
        assertTrue(fields(noTitle).contains("title"));
        assertTrue(fields(createTest(s, null, "x")).contains("variant"), "IELTS has two variants: one must be chosen");
        assertTrue(fields(createTest(s, "XX", "x")).contains("variant"));
        Res ok = createTest(s, "GT", "General Training mock");
        assertEquals(201, ok.status(), ok.raw());
        assertEquals("GT", ok.body().path("variant").asText());
        assertEquals(0, ok.body().path("parts").size());
        assertEquals(4, ok.body().path("blockers").size(), "an empty IELTS test misses all four skills");
        Staff toeic = staff("TOEIC");
        assertTrue(fields(createTest(toeic, "AC", "x")).contains("variant"), "TOEIC has no variants");
        assertEquals(422, post("/api/cms/tests", Map.of("examCode", "NOPE", "title", "x"), s.author(), s.org()).status());
        // a reviewer, a learner and the author of another exam do not create
        UUID learner = user("learner");
        member(s.org(), learner);
        grant(learner, s.org(), null, "LEARNER");
        Map<String, Object> body = Map.of("examCode", "IELTS", "variant", "AC", "title", "x");
        assertEquals(403, post("/api/cms/tests", body, s.reviewer(), s.org()).status());
        assertEquals(403, post("/api/cms/tests", body, learner, s.org()).status());
        UUID toeicAuthor = user("toeic-author");
        grant(toeicAuthor, s.org(), "TOEIC", "AUTHOR");
        assertEquals(403, post("/api/cms/tests", body, toeicAuthor, s.org()).status());
        // nobody else reads it: a learner, the author of another exam and another organisation's author all get a 404 (the header is not theirs to see)
        String id = ok.body().path("testId").asText();
        assertEquals(404, get("/api/cms/tests/" + id, learner, s.org()).status());
        assertEquals(404, get("/api/cms/tests/" + id, toeicAuthor, s.org()).status());
        Staff other = staff("IELTS");
        assertEquals(404, get("/api/cms/tests/" + id, other.author(), other.org()).status());
        assertEquals(404, put("/api/cms/tests/" + id + "/structure", Map.of("parts", List.of()), other.author(), other.org()).status());
        assertEquals(404, post("/api/cms/tests/" + id + "/submit", null, other.author(), other.org()).status());
    }

    @Test
    void aStructureIsCheckedByPath() {
        Staff s = staff("TOEIC");
        UUID listening = publishedGroup("TOEIC_P1_PHOTO", s);
        UUID reading = publishedGroup("TOEIC_P5_INCOMPLETE_SENTENCE", s);
        String id = createTest(s, null, "t").body().path("testId").asText();
        Staff other = staff("TOEIC");
        UUID foreign = publishedGroup("TOEIC_P5_INCOMPLETE_SENTENCE", other);
        Map<String, Object> badDuration = new HashMap<>(part("P1", 600, listening));
        badDuration.put("durationSec", 0);
        Res r = structure(id, s, List.of(
                badDuration,                                  // parts[0]: no time
                part("P9", 600, reading),                     // parts[1]: not a section of TOEIC
                part("P5", 600, reading, reading),            // parts[2]: a group twice (and P5 is fine, but reading was already used in parts[1])
                part("P6", 600, foreign),                     // parts[3]: a group of another organisation
                part("P7", 600)));                            // parts[4]: no group
        assertEquals(422, r.status(), r.raw());
        List<String> at = fields(r);
        for (String expected : List.of("parts[0].durationSec", "parts[1].sectionCode", "parts[2].groups[1]", "parts[3].groups[0]", "parts[4].groups")) {
            assertTrue(at.contains(expected), expected + " in " + at);
        }
        assertEquals(1, r.body().path("errors").get(0).path("row").asInt() + 1, "the row of the first error is its part index: " + r.raw());
        // the same section twice, a question of another section in a part, too many parts
        Res sameSection = structure(id, s, List.of(part("P1", 600, listening), part("P1", 600, reading)));
        assertTrue(fields(sameSection).contains("parts[1].sectionCode"), sameSection.raw());
        Res wrongSection = structure(id, s, List.of(part("P1", 600, reading)));
        assertEquals(422, wrongSection.status());
        assertTrue(fields(wrongSection).contains("parts[0].groups[0]"), "a Part 5 question is not asked in Part 1: " + wrongSection.raw());
        assertEquals(422, structure(id, s, List.of()).status(), "a structure has parts");
        assertEquals(422, put("/api/cms/tests/" + id + "/structure", Map.of("maxResumes", 99, "parts", List.of(part("P1", 600, listening))), s.author(), s.org()).status());
        // nothing of all that was saved
        assertEquals(0, get("/api/cms/tests/" + id, s.author(), s.org()).body().path("parts").size());
    }

    @Test
    void aTestWithoutASkillIsNotSentForReviewAndAGoodOneIsLockedWhileItIsReviewed() {
        Staff s = staff("TOEIC");
        UUID listening = publishedGroup("TOEIC_P1_PHOTO", s);
        UUID reading = publishedGroup("TOEIC_P5_INCOMPLETE_SENTENCE", s);
        Res created = createTest(s, null, "Half a test");
        String id = created.body().path("testId").asText();
        // nothing yet: every skill is missing
        Res none = post("/api/cms/tests/" + id + "/submit", null, s.author(), s.org());
        assertEquals(422, none.status(), none.raw());
        assertEquals("urn:e9x:problem:test-skill-missing", none.body().path("type").asText());
        // only Listening: Reading is missing
        Res onlyListening = structure(id, s, List.of(part("P1", 600, listening)));
        assertEquals(1, onlyListening.body().path("blockers").size());
        assertEquals("READING", onlyListening.body().path("blockers").get(0).path("skill").asText());
        Res blocked = post("/api/cms/tests/" + id + "/submit", null, s.author(), s.org());
        assertEquals(422, blocked.status());
        assertEquals("READING", blocked.body().path("errors").get(0).path("field").asText());
        // both skills: it goes (the counts only warn)
        Res both = structure(id, s, List.of(part("P1", 600, listening), part("P5", 600, reading)));
        assertEquals(0, both.body().path("blockers").size());
        assertEquals(202, post("/api/cms/tests/" + id + "/submit", null, s.author(), s.org()).status());
        // under review: no edit, no second submit
        Res locked = structure(id, s, List.of(part("P1", 900, listening), part("P5", 600, reading)));
        assertEquals(409, locked.status());
        assertEquals("urn:e9x:problem:version-under-review", locked.body().path("type").asText());
        assertEquals(409, post("/api/cms/tests/" + id + "/submit", null, s.author(), s.org()).status());
        // the child rows are frozen in the database too
        UUID version = UUID.fromString(both.body().path("versionId").asText());
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> su.update("delete from test_part_groups where test_version_id = ?", version)).getMessage().contains("cannot change"));
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> su.update("update test_parts set duration_sec = 5 where test_version_id = ?", version)).getMessage().contains("cannot change"));
    }

    @Test
    void aColleagueCannotTakeADraftOverAndEditingAPublishedTestMakesVersion2() {
        Staff s = staff("TOEIC");
        Built built = buildToeicTest(s, "Shared");
        String id = built.test().body().path("testId").asText();
        UUID colleague = user("colleague");
        grant(colleague, s.org(), "TOEIC", "AUTHOR");
        List<Map<String, Object>> parts = List.of(part("P1", 600, built.listeningGroup()), part("P5", 600, built.readingGroup()));
        assertEquals(409, put("/api/cms/tests/" + id + "/structure", Map.of("parts", parts), colleague, s.org()).status());
        assertEquals(409, post("/api/cms/tests/" + id + "/submit", null, colleague, s.org()).status());
        approveTest(built.test(), s);
        // published: the colleague may start version 2
        Res v2 = put("/api/cms/tests/" + id + "/structure", Map.of("parts", parts), colleague, s.org());
        assertEquals(201, v2.status(), v2.raw());
        assertEquals(2, v2.body().path("versionNo").asInt());
        assertEquals(colleague.toString(), v2.body().path("createdBy").asText());
        List<String> statuses = new ArrayList<>();
        get("/api/cms/tests/" + id + "/versions", colleague, s.org()).body().forEach(v -> statuses.add(v.path("status").asText()));
        assertEquals(List.of("PUBLISHED", "DRAFT"), statuses);
        // and the first author can no longer touch that draft
        assertEquals(409, put("/api/cms/tests/" + id + "/structure", Map.of("parts", parts), s.author(), s.org()).status());
    }

    /** Just enough JSON access for the first test. */
    private record JsonNodeView(Res r) {
        String text(String f) {
            return r.body().path(f).asText();
        }

        int int_(String f) {
            return r.body().path(f).asInt();
        }
    }
}
