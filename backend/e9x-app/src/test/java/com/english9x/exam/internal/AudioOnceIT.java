package com.english9x.exam.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AudioOnceIT extends ExamIT {

    @Test
    void secondFetchGone() {
        Room room = room();
        UUID learner = learner(room);
        clock.set(Instant.now());
        Res view = startOk(room, learner);
        UUID attempt = attemptId(view);
        UUID media = UUID.fromString(view.body().path("currentSection").path("groups").get(0).path("content").path("audioMediaId").asText());
        String path = "/api/exam/attempts/" + attempt + "/audio/" + media;
        Res first = get(path, learner, room.staff().org());
        assertEquals(200, first.status(), first.raw());
        assertTrue(first.body().path("url").asText().startsWith("http"), first.raw());
        Res second = get(path, learner, room.staff().org());
        assertEquals(410, second.status());
        assertEquals(1, su.queryForObject("select count(*) from audio_plays where attempt_id = ?", Integer.class, attempt));
        // a file that is not part of the current section is not given out at all
        assertEquals(404, get("/api/exam/attempts/" + attempt + "/audio/" + UUID.randomUUID(), learner, room.staff().org()).status());
        // nor to somebody else
        UUID other = learner(room);
        assertEquals(404, get(path, other, room.staff().org()).status());
    }
}
