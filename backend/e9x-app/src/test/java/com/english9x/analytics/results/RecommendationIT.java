package com.english9x.analytics.results;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A recommendation points only to method lessons the person may open: a lesson of a course they hold no (valid) entitlement to is never named. */
class RecommendationIT extends ResultsHarness {

    private static final String P1 = "TOEIC_P1_PHOTO";
    private static final String P5 = "TOEIC_P5_INCOMPLETE_SENTENCE";

    @Test
    void onlyEntitledLessons() {
        Room room = room();
        UUID learner = learner(room);
        sat(room, learner, "B", "C"); // both question types wrong: both are weak (rate 0)

        UUID mine = methodLesson(P5, "How to read a sentence gap", learner, Duration.ofDays(1), Duration.ofDays(30));
        methodLesson(P1, "Photo method that I did not buy", null, Duration.ZERO, Duration.ZERO);                          // no entitlement at all
        methodLesson(P5, "Gap method of an expired plan", learner, Duration.ofDays(60), Duration.ofDays(30));            // the plan ended 30 days ago
        methodLesson(P5, "Gap method of a plan not started", learner, Duration.ofDays(-5), Duration.ofDays(30));        // starts in 5 days

        Res r = get("/api/results/weakness?exam=TOEIC", learner, room.staff().org());
        assertEquals(200, r.status(), r.raw());
        JsonNode recs = r.body().path("recommendations");
        assertEquals(1, recs.size(), r.raw());
        assertEquals(P5, recs.get(0).path("typeCode").asText());
        assertEquals(1, recs.get(0).path("lessons").size());
        JsonNode lesson = recs.get(0).path("lessons").get(0);
        assertEquals(mine.toString(), lesson.path("courseId").asText());
        assertEquals("How to read a sentence gap", lesson.path("title").asText());
        assertEquals(false, r.raw().contains("did not buy"), "the title of a lesson the person cannot open is not even named");
        assertEquals(false, r.raw().contains("expired plan"));

        // another person, entitled to nothing, is pointed to nothing (and learns nothing of the lessons above)
        UUID stranger = learner(room);
        sat(room, stranger, "B", "C");
        JsonNode theirs = get("/api/results/weakness?exam=TOEIC", stranger, room.staff().org()).body();
        assertEquals(0, theirs.path("recommendations").size());
        assertEquals(2, theirs.path("types").size());
    }
}
