package com.english9x.content.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.catalog.api.ResolvedScoreTable;
import com.english9x.catalog.api.ScoreTables;
import com.english9x.content.test.api.TestExamView;
import com.english9x.content.test.api.TestVersions;
import com.english9x.workflow.api.UnpublishedDependencyException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code TestVersions#forExamRoom}: what the exam room is given of a test, with every solution-side value planted in the data and searched for in the
 * serialised answer; the workflow dependency on the groups; an attempt's version staying readable once a newer one replaced it.
 */
class TestVersionViewIT extends TestIT {

    @Autowired TestVersions testVersions;
    @Autowired ScoreTables scoreTables;

    @Test
    void examRoomDtoHasNoAnswerKey() throws Exception {
        Staff s = staff("TOEIC");
        Built built = buildToeicTest(s, "Mock test 1");
        UUID version = approveTest(built.test(), s);

        TestExamView view = as(s.author(), s.org(), () -> testVersions.forExamRoom(version)).orElseThrow();
        assertEquals("Mock test 1", view.title());
        assertEquals(List.of("P1", "P5"), view.parts().stream().map(TestExamView.Part::sectionCode).toList());
        assertEquals(List.of("LISTENING", "READING"), view.parts().stream().map(TestExamView.Part::skill).toList());
        assertEquals(1, view.parts().get(0).groups().get(0).questions().size(), "the question is there");
        assertEquals("A passage to read.", view.parts().get(0).groups().get(0).content().path("passage").asText(), "and the stimulus");
        assertEquals("The manager ------- the report yesterday.", view.parts().get(1).groups().get(0).questions().get(0).content().path("stem").asText());

        // serialised the way the API will serialise it
        String json = JSON.writeValueAsString(view);
        // the names of the solution side are not even field names
        List<String> names = new ArrayList<>();
        names(JSON.readTree(json), names);
        for (String name : names) {
            for (String word : List.of("answer", "transcript", "sampleAnswer", "explanation", "solution", "notes")) {
                assertFalse(name.toLowerCase().contains(word.toLowerCase()), "a field named " + name);
            }
        }
        // and the words are nowhere in the text (case-sensitive: the type codes are upper case)
        for (String word : List.of("answer", "transcript", "sampleAnswer", "explanation")) {
            assertFalse(json.contains(word), "the exam-room JSON contains \"" + word + "\": " + json);
        }
        // and none of the planted values of the solution side leaks: the answer key's transcript, the explanation, the group's transcript and notes
        for (String secret : List.of(KEY_SECRET, EXPLANATION_SECRET, TRANSCRIPT_SECRET, NOTES_SECRET, "SECRET")) {
            assertFalse(json.contains(secret), "the exam-room JSON repeats " + secret);
        }
        // the secrets really are in the staff view of the same data, so the scan above looked for something that exists
        UUID group = built.listeningGroup();
        String staffGroup = as(s.author(), s.org(), () -> groupVersions.find(group).orElseThrow().solution().toString());
        assertTrue(staffGroup.contains(TRANSCRIPT_SECRET) && staffGroup.contains(NOTES_SECRET), staffGroup);
        assertTrue(su.queryForObject("select count(*) from question_versions where explanation = ? and answer_key::text like ?", Integer.class, EXPLANATION_SECRET, "%" + KEY_SECRET + "%") >= 1);

        // the structural guarantee: no component of any exam-room record can hold a solution field
        for (Class<?> dto : List.of(TestExamView.class, TestExamView.Part.class, TestExamView.Group.class)) {
            for (var c : dto.getRecordComponents()) {
                for (String word : List.of("answer", "transcript", "sampleanswer", "explanation", "solution", "notes")) {
                    assertFalse(c.getName().toLowerCase().contains(word), dto.getSimpleName() + "." + c.getName());
                }
            }
        }
    }

    @Autowired com.english9x.content.question.api.QuestionGroupVersions groupVersions;

    private static void names(JsonNode n, List<String> out) {
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> {
                out.add(e.getKey());
                names(e.getValue(), out);
            });
        } else if (n.isArray()) {
            n.forEach(c -> names(c, out));
        }
    }

    @Test
    void publishBlockedUntilAllGroupsPublished() {
        Staff s = staff("TOEIC");
        UUID listening = publishedGroup("TOEIC_P1_PHOTO", s);
        // a group that is still a draft (its question is published, the group is not reviewed yet)
        Res draftGroup = group(s, "Draft group", List.of(publishedQuestion("TOEIC_P5_INCOMPLETE_SENTENCE", s)));
        UUID draft = UUID.fromString(draftGroup.body().path("versionId").asText());
        Res test = createTest(s, null, "Waits for its group");
        String testId = test.body().path("testId").asText();
        // a draft group may be listed while the test is being written
        Res saved = structure(testId, s, List.of(part("P1", 600, listening), part("P5", 1200, draft)));
        assertEquals(200, saved.status(), saved.raw());
        Res submitted = post("/api/cms/tests/" + testId + "/submit", null, s.author(), s.org());
        assertEquals(202, submitted.status(), submitted.raw());
        UUID review = UUID.fromString(submitted.body().path("reviewId").asText());
        // the reviewer cannot approve it: the group is not PUBLISHED
        assertThrows(UnpublishedDependencyException.class, () -> as(s.reviewer(), s.org(), () -> {
            reviews.approve(review);
            return null;
        }));
        UUID version = UUID.fromString(saved.body().path("versionId").asText());
        assertEquals("IN_REVIEW", su.queryForObject("select status from test_versions where id = ?", String.class, version));
        assertTrue(as(s.author(), s.org(), () -> testVersions.forExamRoom(version)).isEmpty(), "and the exam room serves nothing of it");

        // publish the group, then the same review goes through
        publishGroup(draftGroup, s);
        as(s.reviewer(), s.org(), () -> {
            reviews.approve(review);
            return null;
        });
        assertEquals("PUBLISHED", su.queryForObject("select status from test_versions where id = ?", String.class, version));
        assertTrue(as(s.author(), s.org(), () -> testVersions.forExamRoom(version)).isPresent());
    }

    @Test
    void anAttemptKeepsReadingItsVersionAfterANewOneIsPublished() {
        Staff s = staff("TOEIC");
        Built built = buildToeicTest(s, "Versioned");
        UUID v1 = approveTest(built.test(), s);
        String testId = built.test().body().path("testId").asText();
        // edit the published test: version 2, a draft; v1 still PUBLISHED and untouched
        Res v2 = put("/api/cms/tests/" + testId + "/structure", java.util.Map.of("title", "Versioned, second edition", "parts",
                List.of(part("P1", 900, built.listeningGroup()), part("P5", 1200, built.readingGroup()))), s.author(), s.org());
        assertEquals(201, v2.status(), v2.raw());
        assertEquals(2, v2.body().path("versionNo").asInt());
        assertEquals("Versioned", as(s.author(), s.org(), () -> testVersions.forExamRoom(v1)).orElseThrow().title());
        UUID second = approveTest(v2, s);
        // v1 is archived and is still served: an attempt that began on it keeps its questions
        assertEquals("ARCHIVED", su.queryForObject("select status from test_versions where id = ?", String.class, v1));
        TestExamView old = as(s.author(), s.org(), () -> testVersions.forExamRoom(v1)).orElseThrow();
        assertEquals("Versioned", old.title());
        assertEquals(600, old.parts().get(0).durationSec());
        TestExamView current = as(s.author(), s.org(), () -> testVersions.forExamRoom(second)).orElseThrow();
        assertEquals("Versioned, second edition", current.title());
        assertEquals(900, current.parts().get(0).durationSec());
        assertEquals(old.testId(), current.testId());
    }

    @Test
    void publishingVersion2OfATestKeepsTheOverride() {
        Staff s = staff("TOEIC");
        Built built = buildToeicTest(s, "With an override");
        UUID v1 = approveTest(built.test(), s);
        UUID testId = UUID.fromString(built.test().body().path("testId").asText());
        // the administrator of the organisation set a conversion table for this test (the header is theirs; here as the owner of the tables)
        UUID table = UUID.randomUUID();
        su.update("insert into score_tables(id,owner_org_id,exam_code,skill,scope,test_id) values (?,?,'TOEIC','READING','TEST',?)", table, s.org(), testId);
        su.update("insert into score_table_versions(score_table_id,owner_org_id,exam_code,version_no,status,entries) values (?,?,'TOEIC',1,'PUBLISHED','[{\"raw\":0,\"scaled\":5},{\"raw\":1,\"scaled\":495}]'::jsonb)", table, s.org());
        ResolvedScoreTable first = as(s.author(), s.org(), () -> scoreTables.resolve("TOEIC", "READING", Optional.of(v1)));
        assertTrue(first.testOverride(), "version 1 resolves the override of its test");
        // the override is listed by the builder, and the structure may declare it
        Res shown = get("/api/cms/tests/" + testId, s.author(), s.org());
        assertEquals(table.toString(), shown.body().path("scoreTableOverrides").get(0).path("scoreTableId").asText());

        Res v2 = put("/api/cms/tests/" + testId + "/structure", java.util.Map.of("parts",
                List.of(part("P1", 700, built.listeningGroup()), part("P5", 1200, built.readingGroup())),
                "scoreTableOverrides", List.of(java.util.Map.of("skill", "READING", "scoreTableId", table.toString()))), s.author(), s.org());
        assertEquals(201, v2.status(), v2.raw());
        UUID second = approveTest(v2, s);
        assertEquals("ARCHIVED", su.queryForObject("select status from test_versions where id = ?", String.class, v1));
        ResolvedScoreTable after = as(s.author(), s.org(), () -> scoreTables.resolve("TOEIC", "READING", Optional.of(second)));
        assertTrue(after.testOverride(), "version 2 keeps the override: it is keyed by the test, not by the version");
        assertEquals(first.scoreTableVersionId(), after.scoreTableVersionId());
        assertTrue(as(s.author(), s.org(), () -> scoreTables.resolve("TOEIC", "READING", Optional.of(v1))).testOverride(), "and the archived version still resolves it");
        // the Listening skill has no override: the exam default, which this fresh database does not have
        assertThrows(com.english9x.catalog.api.ScoreTableNotFoundException.class,
                () -> as(s.author(), s.org(), () -> scoreTables.resolve("TOEIC", "LISTENING", Optional.of(second))));
        // an override that does not exist is refused, by path
        Res bad = put("/api/cms/tests/" + testId + "/structure", java.util.Map.of("parts", List.of(part("P1", 700, built.listeningGroup())),
                "scoreTableOverrides", List.of(java.util.Map.of("skill", "LISTENING", "scoreTableId", UUID.randomUUID().toString()))), s.author(), s.org());
        assertEquals(422, bad.status(), bad.raw());
        assertEquals("scoreTableOverrides[0].scoreTableId", bad.body().path("errors").get(0).path("field").asText());
    }
}
