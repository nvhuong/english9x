package com.english9x.content.test;

import com.english9x.content.question.QuestionIT;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Harness of the test-builder tests: a TOEIC organisation (an AUTHOR and a REVIEWER of TOEIC, TOEIC being the exam whose two skills need two groups), the
 * valid examples of the question types, and helpers that publish a question group through the real review so that a test can name it. SECRET markers are
 * planted on every solution-side field (answer key, explanation, transcript of the group, notes) so that a test can search for them.
 */
public abstract class TestIT extends QuestionIT {

    protected static final String KEY_SECRET = "Script of the recording, for the review page.";
    protected static final String EXPLANATION_SECRET = "SECRET-EXPLANATION-xyz";
    protected static final String TRANSCRIPT_SECRET = "SECRET-GROUP-TRANSCRIPT-xyz";
    protected static final String NOTES_SECRET = "SECRET-GROUP-NOTES-xyz";

    /** A question of the type (with its explanation), PUBLISHED through the review. @return its version id */
    protected UUID publishedQuestion(String typeCode, Staff s) {
        Map<String, Object> body = body(typeCode, example(typeCode, s));
        body.put("explanation", EXPLANATION_SECRET);
        Res created = post("/api/cms/questions", body, s.author(), s.org());
        if (created.status() != 201) {
            throw new AssertionError(created.status() + " " + created.raw());
        }
        submitAndApprove(created, s);
        return UUID.fromString(created.body().path("versionId").asText());
    }

    /** A group over the given question versions; a DRAFT. */
    protected Res group(Staff s, String title, List<UUID> questionVersions) {
        ObjectNode content = JSON.createObjectNode().put("title", title).put("passage", "A passage to read.").put("audioMediaId", s.audio().toString());
        ObjectNode solution = JSON.createObjectNode().put("transcript", TRANSCRIPT_SECRET).put("notes", NOTES_SECRET);
        Res r = post("/api/cms/question-groups", Map.of("examCode", s.exam(), "content", content, "solution", solution,
                "questionVersionIds", questionVersions.stream().map(UUID::toString).toList()), s.author(), s.org());
        if (r.status() != 201) {
            throw new AssertionError(r.status() + " " + r.raw());
        }
        return r;
    }

    /** A group of one question of the type, PUBLISHED through the review. @return the version id of the group */
    protected UUID publishedGroup(String typeCode, Staff s) {
        Res g = group(s, "Group of " + typeCode, List.of(publishedQuestion(typeCode, s)));
        publishGroup(g, s);
        return UUID.fromString(g.body().path("versionId").asText());
    }

    protected void publishGroup(Res g, Staff s) {
        Res submitted = post("/api/cms/question-groups/" + g.body().path("groupId").asText() + "/submit", null, s.author(), s.org());
        if (submitted.status() != 202) {
            throw new AssertionError(submitted.status() + " " + submitted.raw());
        }
        UUID review = UUID.fromString(submitted.body().path("reviewId").asText());
        as(s.reviewer(), s.org(), () -> {
            reviews.approve(review);
            return null;
        });
    }

    protected Res createTest(Staff s, String variant, String title) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("examCode", s.exam());
        body.put("variant", variant);
        body.put("title", title);
        return post("/api/cms/tests", body, s.author(), s.org());
    }

    protected static Map<String, Object> part(String section, int seconds, UUID... groups) {
        return Map.of("sectionCode", section, "durationSec", seconds, "groups", java.util.Arrays.stream(groups).map(UUID::toString).toList());
    }

    protected Res structure(String testId, Staff s, List<Map<String, Object>> parts) {
        return put("/api/cms/tests/" + testId + "/structure", Map.of("parts", parts), s.author(), s.org());
    }

    protected UUID approveTest(Res testVersion, Staff s) {
        Res submitted = post("/api/cms/tests/" + testVersion.body().path("testId").asText() + "/submit", null, s.author(), s.org());
        if (submitted.status() != 202) {
            throw new AssertionError(submitted.status() + " " + submitted.raw());
        }
        UUID review = UUID.fromString(submitted.body().path("reviewId").asText());
        as(s.reviewer(), s.org(), () -> {
            reviews.approve(review);
            return null;
        });
        return UUID.fromString(testVersion.body().path("versionId").asText());
    }

    /** A TOEIC test with a Listening part (a photo) and a Reading part (a sentence) whose groups are PUBLISHED, as a DRAFT test; both groups' versions in the list. */
    protected record Built(Res test, UUID listeningGroup, UUID readingGroup) {}

    protected Built buildToeicTest(Staff s, String title) {
        UUID listening = publishedGroup("TOEIC_P1_PHOTO", s);
        UUID reading = publishedGroup("TOEIC_P5_INCOMPLETE_SENTENCE", s);
        Res created = createTest(s, null, title);
        if (created.status() != 201) {
            throw new AssertionError(created.status() + " " + created.raw());
        }
        Res saved = structure(created.body().path("testId").asText(), s, List.of(part("P1", 600, listening), part("P5", 1200, reading)));
        if (saved.status() != 200) {
            throw new AssertionError(saved.status() + " " + saved.raw());
        }
        return new Built(saved, listening, reading);
    }
}
