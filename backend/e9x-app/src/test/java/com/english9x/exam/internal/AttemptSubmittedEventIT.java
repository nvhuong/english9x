package com.english9x.exam.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttemptSubmittedEventIT extends ExamIT {

    @Test
    void publishedOnce() {
        Room room = room();
        UUID learner = learner(room);
        clock.set(Instant.now());
        UUID attempt = attemptId(startOk(room, learner));
        UUID org = room.staff().org();
        assertEquals(0, submitted.count(attempt));
        Res first = post("/api/exam/attempts/" + attempt + "/submit", null, learner, org);
        Res second = post("/api/exam/attempts/" + attempt + "/submit", null, learner, org);
        assertEquals(200, first.status());
        assertEquals(200, second.status(), "idempotent: the second call is not an error");
        assertEquals(first.raw(), second.raw(), "and says the same");
        assertEquals("LEARNER", first.body().path("reason").asText());
        assertEquals(1, submitted.count(attempt));
        var event = submitted.all().stream().filter(e -> e.attemptId().equals(attempt)).findFirst().orElseThrow();
        assertEquals(learner, event.userId());
        assertEquals(org, event.orgId().orElseThrow());
        assertEquals(false, event.interrupted());
        // a submitted attempt takes no more sections, and a late answer of an earlier section is still judged by the domain's grace, not by the status
        assertEquals(200, post("/api/exam/attempts/" + attempt + "/resume", null, learner, org).status() == 409 ? 200 : 500);
    }
}
