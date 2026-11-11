package com.english9x.learning.internal;

import com.english9x.learning.internal.LearningDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The learner's API (F22, F23): a person's own lessons, practice, tests and study time. Never carries an answer key (see {@link LearningService}). */
@RestController
class LearningController {

    private final LearningService service;

    LearningController(LearningService service) {
        this.service = service;
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/api/learning/courses/{courseId}/map")
    @Operation(summary = "The chapter map of a course: every lesson with its state (done, best score, locked) and the streak. 403 without a plan.")
    ResponseEntity<ChapterMapView> map(@PathVariable UUID courseId) {
        return noStore(service.map(courseId));
    }

    @GetMapping("/api/learning/lessons/{id}")
    @Operation(summary = "A strategy lesson as the learner reads it (blocks and the questions embedded in it, without answers)",
            description = "403 without a plan, 409 while the lesson is locked by the unlock rule, 404 for a lesson that is not one.")
    ResponseEntity<LessonView> lesson(@PathVariable UUID id) {
        return noStore(service.lesson(id));
    }

    @PostMapping("/api/learning/lessons/{id}/practice")
    @Operation(summary = "Answer a question embedded in the lesson and see the result at once",
            description = "The one place a learner is shown an answer: the explanation and the correct answer of a practice question just answered, in a lesson they may open. 404 for any other question.")
    ResponseEntity<PracticeResult> practice(@PathVariable UUID id, @RequestBody PracticeRequest body) {
        return noStore(service.practice(id, body));
    }

    @PostMapping("/api/learning/lessons/{id}/complete")
    @Operation(summary = "Mark the lesson finished (counts for the unlock rule and the day's streak)")
    ResponseEntity<Completion> complete(@PathVariable UUID id, @RequestHeader(value = "X-Time-Zone", required = false) String zone) {
        return noStore(service.complete(id, zone));
    }

    @GetMapping("/api/learning/chapter-tests/{id}")
    @Operation(summary = "A chapter test (the lesson id of its place in the course): its questions without answers, the attempts used and the best score")
    ResponseEntity<ChapterTestView> chapterTest(@PathVariable UUID id) {
        return noStore(service.chapterTest(id));
    }

    @PostMapping("/api/learning/chapter-tests/{id}/attempts")
    @Operation(summary = "Sit the chapter test: submit the answers, get the score; the best score is kept",
            description = "409 when the attempts the course allows are used up.")
    ResponseEntity<AttemptResult> attempt(@PathVariable UUID id, @RequestBody AttemptRequest body, @RequestHeader(value = "X-Time-Zone", required = false) String zone) {
        return noStore(service.attempt(id, body, zone));
    }

    @PostMapping("/api/learning/heartbeat")
    @Operation(summary = "The learner is studying now (every e9x.learning.heartbeat-interval, only while the page is visible and used)",
            description = "The server counts the gaps between signals that are at most e9x.learning.idle-timeout; days are cut in the learner's own time zone.")
    ResponseEntity<HeartbeatResult> heartbeat(@RequestBody HeartbeatRequest body) {
        return noStore(service.heartbeat(body));
    }
}
