package com.english9x.exam.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExamAttemptIT extends ExamIT {

    private record Begun(Room room, UUID learner, UUID attempt, Res view, Instant t0) {}

    private Begun begin() {
        Room room = room();
        UUID u = learner(room);
        Instant t0 = Instant.now();
        clock.set(t0);
        Res r = startOk(room, u);
        return new Begun(room, u, attemptId(r), r, t0);
    }

    @Test
    void startReturnsTheFirstSectionOnly() {
        Begun b = begin();
        assertEquals("IN_PROGRESS", b.view().body().path("status").asText());
        assertEquals(2, b.view().body().path("sections").size());
        assertEquals("P1", b.view().body().path("currentSection").path("sectionId").asText());
        assertEquals(b.t0().plusSeconds(600), Instant.parse(b.view().body().path("endsAt").asText()));
        assertEquals(b.t0(), Instant.parse(b.view().body().path("serverNow").asText()));
        assertTrue(!b.view().raw().contains("P5 ") && b.view().body().path("currentSection").path("groups").size() == 1, "the later section is not sent before its turn");
        // a job waits for the deadline of the whole attempt
        assertEquals(1, su.queryForObject("select count(*) from jobs where type = 'auto_submit_attempt' and payload->>'attemptId' = ?", Integer.class, b.attempt().toString()));
        assertEquals(b.t0().plusSeconds(1800).toEpochMilli(),
                su.queryForObject("select run_at from jobs where payload->>'attemptId' = ?", java.sql.Timestamp.class, b.attempt().toString()).toInstant().toEpochMilli());
    }

    @Test
    void startWithoutAPlanIsRefused() {
        Room room = room();
        UUID u = user("noplan");
        member(room.staff().org(), u);
        assertEquals(403, start(room, u).status());
    }

    @Test
    void secondStartReturnsExisting() {
        Begun b = begin();
        Res again = start(b.room(), b.learner());
        assertEquals(409, again.status());
        assertEquals(b.attempt(), attemptId(again));
        assertEquals(1, su.queryForObject("select count(*) from exam_attempts where user_id = ?", Integer.class, b.learner()));
    }

    @Test
    void anotherPersonCannotTouchMyAttempt() {
        Begun b = begin();
        UUID other = learner(b.room());
        assertEquals(404, get("/api/exam/attempts/" + b.attempt(), other, b.room().staff().org()).status());
        assertEquals(404, post("/api/exam/attempts/" + b.attempt() + "/submit", null, other, b.room().staff().org()).status());
    }

    @Test
    void offlineAnswerSyncedWithinGraceAccepted() {
        Begun b = begin();
        UUID q = firstQuestion(b.view());
        clock.set(b.t0().plusSeconds(601)); // P1 ended at +600; the grace is 2 minutes
        assertEquals(204, save(b.room(), b.learner(), b.attempt(), q, "B", b.t0().plusSeconds(300)).status());
        assertEquals("\"B\"", su.queryForObject("select answer::text from responses where attempt_id = ? and question_id = ?", String.class, b.attempt(), q));
    }

    @Test
    void offlineAnswerAfterGraceRejected() {
        Begun b = begin();
        UUID q = firstQuestion(b.view());
        clock.set(b.t0().plusSeconds(600 + 121));
        assertEquals(409, save(b.room(), b.learner(), b.attempt(), q, "B", b.t0().plusSeconds(300)).status());
        assertEquals(0, su.queryForObject("select count(*) from responses where attempt_id = ?", Integer.class, b.attempt()));
    }

    @Test
    void responseRejectedAfterSectionEnd() {
        Begun b = begin();
        UUID q = firstQuestion(b.view());
        clock.set(b.t0().plusSeconds(602));
        // written after the section ended: never accepted, however soon it is synced
        assertEquals(409, save(b.room(), b.learner(), b.attempt(), q, "B", b.t0().plusSeconds(601)).status());
        // a time in the future is not believed either
        assertEquals(409, save(b.room(), b.learner(), b.attempt(), q, "B", b.t0().plusSeconds(700)).status());
    }

    @Test
    void newerAnswerWinsAndOlderLosesAnyOrder() {
        Begun b = begin();
        UUID q = firstQuestion(b.view());
        clock.set(b.t0().plusSeconds(100));
        assertEquals(204, save(b.room(), b.learner(), b.attempt(), q, "B", b.t0().plusSeconds(50)).status());
        assertEquals(204, save(b.room(), b.learner(), b.attempt(), q, "C", b.t0().plusSeconds(60)).status());
        assertEquals(204, save(b.room(), b.learner(), b.attempt(), q, "A", b.t0().plusSeconds(40)).status()); // late arrival of an older one: ignored
        assertEquals("\"C\"", su.queryForObject("select answer::text from responses where attempt_id = ? and question_id = ?", String.class, b.attempt(), q));
        // not a question of this attempt
        assertEquals(404, save(b.room(), b.learner(), b.attempt(), UUID.randomUUID(), "A", b.t0().plusSeconds(40)).status());
    }

    @Test
    void resumeKeepsWhatWasSavedAndCountsUp() {
        Begun b = begin();
        UUID q = firstQuestion(b.view());
        clock.set(b.t0().plusSeconds(10));
        assertEquals(204, save(b.room(), b.learner(), b.attempt(), q, "D", b.t0().plusSeconds(5)).status());
        Res r = post("/api/exam/attempts/" + b.attempt() + "/resume", null, b.learner(), b.room().staff().org());
        assertEquals(200, r.status(), r.raw());
        assertEquals(1, r.body().path("resumeCount").asInt());
        assertEquals("D", r.body().path("saved").get(0).path("value").asText());
    }

    @Test
    void resumeBeyondLimitSubmitsInterrupted() {
        Begun b = begin();
        su.update("update exam_attempts set max_resumes = 1 where id = ?", b.attempt());
        assertEquals(200, post("/api/exam/attempts/" + b.attempt() + "/resume", null, b.learner(), b.room().staff().org()).status());
        Res over = post("/api/exam/attempts/" + b.attempt() + "/resume", null, b.learner(), b.room().staff().org());
        assertEquals(409, over.status(), over.raw());
        assertEquals("SUBMITTED", over.body().path("status").asText());
        assertEquals("RESUME_LIMIT", over.body().path("result").path("reason").asText());
        assertTrue(over.body().path("result").path("interrupted").asBoolean());
        assertEquals(true, su.queryForObject("select interrupted from exam_attempts where id = ?", Boolean.class, b.attempt()));
        assertEquals(1, submitted.count(b.attempt()));
    }

    @Test
    void resumeAfterTheDeadlineAnswers409WithTheSubmittedResult() {
        Begun b = begin();
        clock.set(b.t0().plus(Duration.ofSeconds(1800))); // (the signed-in token would have expired by now, so the service is called as the person)
        ExamService.Resumed r;
        try (var s = com.english9x.org.api.CurrentContextHolder.bind(b.learner(), b.room().staff().org())) {
            r = service.resume(b.attempt());
        }
        assertEquals(com.english9x.domain.exam.ResumeOutcome.AUTO_SUBMITTED, r.outcome());
        assertEquals("TIME_UP", r.view().result().reason());
        assertEquals(b.t0().plusSeconds(1800), r.view().result().submittedAt());
        assertEquals(1, submitted.count(b.attempt()));
    }

    @Test
    void finishingASectionMovesOnAndMovesTheDeadlineForward() {
        Begun b = begin();
        clock.set(b.t0().plusSeconds(100));
        Res wrong = post("/api/exam/attempts/" + b.attempt() + "/sections/P5/finish", null, b.learner(), b.room().staff().org());
        assertEquals(409, wrong.status());
        Res r = post("/api/exam/attempts/" + b.attempt() + "/sections/P1/finish", null, b.learner(), b.room().staff().org());
        assertEquals(200, r.status(), r.raw());
        assertEquals("P5", r.body().path("currentSection").path("sectionId").asText());
        assertEquals(b.t0().plusSeconds(1300), Instant.parse(r.body().path("endsAt").asText()));
        assertEquals(2, su.queryForObject("select count(*) from jobs where type = 'auto_submit_attempt' and payload->>'attemptId' = ?", Integer.class, b.attempt().toString()),
                "a second job waits for the earlier deadline");
    }

    @Test
    void anExpiredEntitlementDoesNotStopAnAttemptThatBeganInTime() {
        Begun b = begin();
        su.update("update entitlements set valid_until = ? where user_id = ?", java.sql.Timestamp.from(b.t0().plusSeconds(5)), b.learner());
        clock.set(b.t0().plusSeconds(60));
        UUID q = firstQuestion(b.view());
        assertEquals(204, save(b.room(), b.learner(), b.attempt(), q, "A", b.t0().plusSeconds(30)).status());
        assertEquals(200, post("/api/exam/attempts/" + b.attempt() + "/submit", null, b.learner(), b.room().staff().org()).status());
    }

    private Res upload(Begun b, UUID question, byte[] bytes, String contentType) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        h.setBearerAuth(com.english9x.support.TestAuth.accessToken(b.learner()));
        h.add("X-Org-Context", b.room().staff().org().toString());
        org.springframework.util.LinkedMultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        org.springframework.http.HttpHeaders part = new org.springframework.http.HttpHeaders();
        part.setContentType(org.springframework.http.MediaType.parseMediaType(contentType));
        part.setContentDispositionFormData("file", "r.webm");
        body.add("file", new org.springframework.http.HttpEntity<>(new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "r.webm";
            }
        }, part));
        return toRes(http.exchange("/api/exam/attempts/" + b.attempt() + "/recordings/" + question, org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(body, h), String.class));
    }

    @Test
    void recordingsOnlyForTheSpeakingPartAndOnlyWebm() {
        Begun b = begin();
        UUID q = firstQuestion(b.view());
        byte[] webm = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 2, 3};
        assertEquals(415, upload(b, q, new byte[] {1, 2, 3, 4, 5}, "audio/webm").status(), "not WebM by its bytes");
        assertEquals(415, upload(b, q, webm, "audio/mpeg").status(), "not WebM by its type");
        assertEquals(409, upload(b, q, webm, "audio/webm").status(), "a Listening question has no recording");
        assertEquals(404, upload(b, UUID.randomUUID(), webm, "audio/webm").status(), "not a question of the attempt");
    }
}
