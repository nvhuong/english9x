package com.english9x.analytics.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The result of one attempt (F38): who may read it, when, and what it says. The answer key shows only after the attempt was scored. */
class ResultsIT extends ResultsHarness {

    private static void assertNoSolution(Res r) {
        for (String secret : new String[] {EXPLANATION_SECRET, KEY_SECRET, TRANSCRIPT_SECRET, NOTES_SECRET, "\"slots\"", "correctAnswer"}) {
            assertFalse(r.raw().contains(secret), "the answer must not carry the solution side (" + secret + "): " + r.raw());
        }
    }

    @Test
    void answerKeyVisibleOnlyAfterSubmit() {
        Room room = room();
        UUID learner = learner(room);
        UUID stranger = learner(room);
        Sat going = start(room, learner, "A", "D"); // P1 and P5 both right, still IN_PROGRESS in the P5 section

        Res open = get("/api/results/attempts/" + going.attempt(), learner, room.staff().org());
        assertEquals(409, open.status(), open.raw());
        assertEquals("urn:e9x:problem:results-attempt-not-submitted", open.body().path("type").asText());
        assertNoSolution(open);

        // submitted, inside the late-sync grace: nobody has scored it, an answer can still arrive, so there is no key yet
        Sat submitted = submit(going);
        Res scoring = get("/api/results/attempts/" + submitted.attempt(), learner, room.staff().org());
        assertEquals(200, scoring.status(), scoring.raw());
        assertEquals("SCORING", scoring.body().path("state").asText());
        assertEquals(0, scoring.body().path("questions").size());
        assertEquals(0, scoring.body().path("skills").size());
        assertNoSolution(scoring);

        // scored: now the key, the explanation, the transcript
        grade(submitted);
        Res ready = get("/api/results/attempts/" + submitted.attempt(), learner, room.staff().org());
        assertEquals(200, ready.status(), ready.raw());
        JsonNode body = ready.body();
        assertEquals("READY", body.path("state").asText());
        assertEquals("TOEIC", body.path("examCode").asText());
        assertFalse(body.path("interrupted").asBoolean());
        assertEquals(2, body.path("questions").size());
        JsonNode p1 = byType(body, "TOEIC_P1_PHOTO");
        assertTrue(p1.path("scored").asBoolean());
        assertTrue(p1.path("correct").asBoolean());
        assertEquals("A", p1.path("correctAnswer").path(0).path(0).asText());
        assertEquals(EXPLANATION_SECRET, p1.path("explanation").asText());
        assertEquals(KEY_SECRET, p1.path("transcript").asText());
        assertTrue(byType(body, "TOEIC_P5_INCOMPLETE_SENTENCE").path("correct").asBoolean());
        assertFalse(ready.raw().contains(TRANSCRIPT_SECRET), "the group's solution is not part of the result");
        assertEquals("OFFICIAL", skill(body, "LISTENING").path("label").asText());
        assertEquals(1.0, skill(body, "LISTENING").path("raw").asDouble());

        // only the owner: another learner (same organisation) and an id nobody has find nothing
        assertEquals(404, get("/api/results/attempts/" + submitted.attempt(), stranger, room.staff().org()).status());
        assertEquals(404, get("/api/results/attempts/" + going.attempt(), stranger, room.staff().org()).status());
        assertEquals(404, get("/api/results/attempts/" + UUID.randomUUID(), learner, room.staff().org()).status());
    }

    @Test
    void wrongAnswerAndBlankAreMarkedWithTheKey() {
        Room room = room();
        UUID learner = learner(room);
        Sat s = sat(room, learner, "B", null); // P1 wrong, P5 left blank
        JsonNode body = get("/api/results/attempts/" + s.attempt(), learner, room.staff().org()).body();
        JsonNode p1 = byType(body, "TOEIC_P1_PHOTO");
        assertFalse(p1.path("correct").asBoolean());
        assertEquals("B", p1.path("answer").asText());
        assertEquals("A", p1.path("correctAnswer").path(0).path(0).asText());
        JsonNode p5 = byType(body, "TOEIC_P5_INCOMPLETE_SENTENCE");
        assertFalse(p5.path("correct").asBoolean());
        assertTrue(p5.path("answer").isNull() || p5.path("answer").isMissingNode(), "no answer given");
        assertEquals(0.0, p5.path("earned").asDouble());
    }

    @Test
    void interruptedFlagAndLabelsComeFromTheStoredScores() {
        Room room = room();
        UUID learner = learner(room);
        Sat s = sat(room, learner, "A", "D");
        // a Writing skill the AI scored at once (PRELIMINARY) ...
        su.update("insert into scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max,scaled) values (?,?,?,'WRITING','PRELIMINARY','AI',6.0,null,6.0)", s.attempt(), learner,
                room.staff().org());
        JsonNode first = get("/api/results/attempts/" + s.attempt(), learner, room.staff().org()).body();
        assertEquals("PRELIMINARY", skill(first, "WRITING").path("label").asText());
        assertEquals("AI", skill(first, "WRITING").path("source").asText());
        assertEquals(6.0, skill(first, "WRITING").path("score").asDouble());
        // ... then the teacher's, which is official and replaces it (the rule of the queue, DisplayedScores)
        su.update("insert into scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max,scaled) values (?,?,?,'WRITING','OFFICIAL','TEACHER',6.5,null,6.5)", s.attempt(), learner,
                room.staff().org());
        JsonNode second = get("/api/results/attempts/" + s.attempt(), learner, room.staff().org()).body();
        assertEquals("OFFICIAL", skill(second, "WRITING").path("label").asText());
        assertEquals("TEACHER", skill(second, "WRITING").path("source").asText());
        assertEquals(6.5, skill(second, "WRITING").path("score").asDouble());
        assertFalse(second.path("interrupted").asBoolean());
    }

    @Test
    void interruptedAttemptIsFlagged() {
        Room room = room();
        UUID learner = learner(room);
        Instant t0 = Instant.now();
        clock.set(t0);
        UUID attempt = attemptId(startOk(room, learner));
        su.update("update exam_attempts set max_resumes = 1 where id = ?", attempt);
        assertEquals(200, post("/api/exam/attempts/" + attempt + "/resume", null, learner, room.staff().org()).status());
        assertEquals(409, post("/api/exam/attempts/" + attempt + "/resume", null, learner, room.staff().org()).status()); // over the limit: submitted as interrupted
        grade(new Sat(room, learner, attempt, t0));
        JsonNode body = get("/api/results/attempts/" + attempt, learner, room.staff().org()).body();
        assertEquals("READY", body.path("state").asText());
        assertTrue(body.path("interrupted").asBoolean(), "the warning label of F29 travels with the result");
    }

    @Test
    void aiEvaluationsAreShownWithoutCostOrTokens() {
        Room room = room();
        UUID learner = learner(room);
        Sat s = sat(room, learner, "A", "D");
        UUID reading = su.queryForObject("select question_id from attempt_questions where attempt_id = ? and section_code = 'P5'", UUID.class, s.attempt());
        // the AI's evaluation of a response: shown with its criteria and feedback, never with the cost or the token counts
        su.update("insert into ai_evaluations(attempt_id,question_id,user_id,owner_org_id,model,input_tokens,output_tokens,cost_vnd,band,result) values (?,?,?,?,'gemini-x',10,10,1,6.5,"
                + "'{\"criteria\":{\"TA\":6.5,\"CC\":6.0},\"feedback\":\"Develop your ideas.\"}'::jsonb)", s.attempt(), reading, learner, room.staff().org());
        JsonNode body = get("/api/results/attempts/" + s.attempt(), learner, room.staff().org()).body();
        JsonNode fb = body.path("feedback").get(0);
        assertEquals("READING", fb.path("skill").asText());
        assertEquals("Develop your ideas.", fb.path("ai").get(0).path("result").path("feedback").asText());
        assertEquals(6.5, fb.path("ai").get(0).path("band").asDouble());
        assertFalse(body.toString().contains("cost"), "no cost or token counts");
        assertFalse(body.toString().contains("input_tokens"));
    }

    private static JsonNode byType(JsonNode body, String type) {
        for (JsonNode q : body.path("questions")) {
            if (type.equals(q.path("typeCode").asText())) {
                return q;
            }
        }
        throw new AssertionError("no question of " + type + " in " + body);
    }

    private static JsonNode skill(JsonNode body, String skill) {
        for (JsonNode s : body.path("skills")) {
            if (skill.equals(s.path("skill").asText())) {
                return s;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
