package com.english9x.exam.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The exam room never carries the solution side: every response of the API is searched for it, serialised as sent. */
class ExamDtoIT extends ExamIT {

    @Test
    void noAnswerKeyInAnyResponse() {
        Room room = room();
        UUID learner = learner(room);
        Instant t0 = Instant.now();
        clock.set(t0);
        UUID org = room.staff().org();
        List<Res> all = new ArrayList<>();
        Res started = startOk(room, learner);
        all.add(started);
        UUID attempt = attemptId(started);
        all.add(start(room, learner)); // 409 with the attempt
        UUID q = firstQuestion(started);
        clock.set(t0.plusSeconds(10));
        assertTrue(save(room, learner, attempt, q, "A", t0.plusSeconds(5)).status() == 204);
        all.add(get("/api/exam/attempts/" + attempt, learner, org));
        all.add(post("/api/exam/attempts/" + attempt + "/resume", null, learner, org));
        UUID media = UUID.fromString(started.body().path("currentSection").path("groups").get(0).path("content").path("audioMediaId").asText());
        all.add(get("/api/exam/attempts/" + attempt + "/audio/" + media, learner, org));
        all.add(post("/api/exam/attempts/" + attempt + "/sections/P1/finish", null, learner, org));
        all.add(post("/api/exam/attempts/" + attempt + "/submit", null, learner, org));
        all.add(get("/api/exam/attempts/" + attempt, learner, org)); // after the submission
        for (Res r : all) {
            for (String word : List.of("answer", "transcript", "sampleAnswer", "explanation", "solution")) {
                assertFalse(r.raw().contains(word), "a response contains \"" + word + "\": " + r.raw());
            }
            for (String secret : List.of(KEY_SECRET, EXPLANATION_SECRET, TRANSCRIPT_SECRET, NOTES_SECRET, "SECRET")) {
                assertFalse(r.raw().contains(secret), "a response repeats " + secret);
            }
        }
        // the secrets exist in the database, so the scan looked for something real
        assertTrue(su.queryForObject("select count(*) from question_versions where explanation = ?", Integer.class, EXPLANATION_SECRET) >= 1);
        // no table of the exam room holds a key either
        for (String table : List.of("exam_attempts", "attempt_sections", "attempt_questions", "responses", "audio_plays", "speaking_recordings")) {
            assertTrue(su.queryForObject("select count(*) from information_schema.columns where table_name = ? and (column_name like '%key%' or column_name like '%solution%' or column_name like '%transcript%' or column_name like '%explanation%')",
                    Integer.class, table) == 0, table + " has a solution column");
        }
    }
}
