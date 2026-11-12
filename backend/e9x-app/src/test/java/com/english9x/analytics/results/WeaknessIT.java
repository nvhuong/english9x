package com.english9x.analytics.results;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The share of right answers per question type over a person's scored attempts (F38). */
class WeaknessIT extends ResultsHarness {

    private static JsonNode type(JsonNode body, String code) {
        for (JsonNode t : body.path("types")) {
            if (code.equals(t.path("typeCode").asText())) {
                return t;
            }
        }
        throw new AssertionError("no type " + code + " in " + body);
    }

    @Test
    void aggregatesAcrossAttempts() {
        Room room = room();
        UUID learner = learner(room);
        UUID other = learner(room);
        sat(room, learner, "A", "C");  // P1 right, P5 wrong
        sat(room, learner, "B", "C");  // P1 wrong, P5 wrong
        sat(room, other, "A", "D");    // somebody else's right answers are not mine
        // a third attempt, submitted but still inside the grace (not scored): it is not a statistic yet
        submit(start(room, learner, "A", "D"));

        Res r = get("/api/results/weakness?exam=TOEIC", learner, room.staff().org());
        assertEquals(200, r.status(), r.raw());
        JsonNode body = r.body();
        assertEquals("TOEIC", body.path("exam").asText());
        assertEquals(2, body.path("attempts").asInt());
        assertEquals(2, body.path("types").size());
        JsonNode p1 = type(body, "TOEIC_P1_PHOTO");
        assertEquals(1, p1.path("correct").asInt());
        assertEquals(2, p1.path("total").asInt());
        assertEquals(0.5, p1.path("rate").asDouble());
        JsonNode p5 = type(body, "TOEIC_P5_INCOMPLETE_SENTENCE");
        assertEquals(0, p5.path("correct").asInt());
        assertEquals(2, p5.path("total").asInt());
        assertEquals(0.0, p5.path("rate").asDouble());
        assertEquals("TOEIC_P5_INCOMPLETE_SENTENCE", body.path("types").get(0).path("typeCode").asText(), "worst first");
        assertEquals(false, p5.path("typeName").asText().isBlank());

        // the other exam has none; the other person's numbers are theirs
        assertEquals(0, get("/api/results/weakness?exam=IELTS", learner, room.staff().org()).body().path("types").size());
        JsonNode theirs = get("/api/results/weakness", other, room.staff().org()).body();
        assertEquals(1, theirs.path("attempts").asInt());
        assertEquals(1.0, type(theirs, "TOEIC_P1_PHOTO").path("rate").asDouble());
        // the statistic is written by the scoring, in the same run as the score (one row per objective question)
        assertEquals(2, su.queryForObject("select count(*) from item_type_stats where attempt_id = (select id from exam_attempts where user_id = ? order by started_at limit 1)", Integer.class, learner));
    }
}
