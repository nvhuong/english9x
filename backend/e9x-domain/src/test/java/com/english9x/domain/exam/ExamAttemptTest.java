package com.english9x.domain.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class ExamAttemptTest {
    private static final Instant T0 = Instant.parse("2026-11-06T08:00:00Z");
    private static final SectionPlan LISTENING = new SectionPlan("listening", Duration.ofMinutes(30));
    private static final SectionPlan READING = new SectionPlan("reading", Duration.ofMinutes(60));

    private static Instant at(long minutes) {
        return T0.plus(Duration.ofMinutes(minutes));
    }

    private static ExamAttempt start(int maxResumes) {
        return ExamAttempt.start("a1", List.of(LISTENING, READING), maxResumes, T0);
    }

    @Test
    public void startsInFirstSection() {
        ExamAttempt a = start(2);
        assertEquals(Optional.of(LISTENING), a.currentSection(at(1)));
        assertEquals(at(30), a.currentSectionEndsAt(at(1)));
        assertEquals(AttemptStatus.IN_PROGRESS, a.status());
        assertEquals(0, a.resumeCount());
        assertFalse(a.interrupted());
    }

    @Test
    public void autoAdvancesWhenSectionTimeUp() {
        ExamAttempt a = start(2);
        assertEquals(Optional.of(READING), a.currentSection(at(31)));
        assertEquals(at(90), a.currentSectionEndsAt(at(31)));
        assertTrue(a.canAnswer("reading", at(31)));
        assertFalse(a.canAnswer("listening", at(31)));
    }

    @Test
    public void autoSubmitsWhenLastSectionTimeUp() {
        ExamAttempt a = start(2);
        assertEquals(Optional.empty(), a.currentSection(at(91)));
        assertEquals(AttemptStatus.SUBMITTED, a.status());
        assertEquals(Optional.of(SubmitReason.TIME_UP), a.submitReason());
        assertEquals(Optional.of(at(90)), a.submittedAt());
        assertThrows(IllegalStateException.class, () -> a.currentSectionEndsAt(at(92)));
        assertFalse(a.canAnswer("reading", at(92)));
    }

    @Test
    public void finishEarlyMovesToNextSection() {
        ExamAttempt a = start(2);
        a.finishSectionEarly(at(10));
        assertEquals(Optional.of(READING), a.currentSection(at(11)));
        assertEquals(at(70), a.currentSectionEndsAt(at(11)));
        a.finishSectionEarly(at(20));
        assertEquals(AttemptStatus.SUBMITTED, a.status());
        assertEquals(Optional.of(SubmitReason.LEARNER), a.submitReason());
    }

    @Test
    public void cannotAnswerPreviousSection() {
        ExamAttempt a = start(2);
        assertTrue(a.canAnswer("listening", at(5)));
        assertFalse(a.canAnswer("reading", at(5)));
        a.finishSectionEarly(at(10));
        assertFalse(a.canAnswer("listening", at(11)));
    }

    @Test
    public void resumeWithinLimitContinues() {
        ExamAttempt a = start(2);
        assertEquals(ResumeOutcome.CONTINUED, a.resume(at(5)));
        assertEquals(ResumeOutcome.CONTINUED, a.resume(at(6)));
        assertEquals(2, a.resumeCount());
        assertEquals(AttemptStatus.IN_PROGRESS, a.status());
        assertFalse(a.interrupted());
    }

    @Test
    public void resumeBeyondLimitAutoSubmitsInterrupted() {
        ExamAttempt a = start(2);
        a.resume(at(1));
        a.resume(at(2));
        assertEquals(ResumeOutcome.AUTO_SUBMITTED, a.resume(at(3)));
        assertEquals(AttemptStatus.SUBMITTED, a.status());
        assertTrue(a.interrupted());
        assertEquals(Optional.of(SubmitReason.RESUME_LIMIT), a.submitReason());
        assertEquals(Optional.of(at(3)), a.submittedAt());
    }

    @Test
    public void submitIsIdempotent() {
        ExamAttempt a = start(2);
        assertTrue(a.submit(at(5), SubmitReason.LEARNER));
        assertFalse(a.submit(at(6), SubmitReason.TIME_UP));
        assertEquals(Optional.of(SubmitReason.LEARNER), a.submitReason());
        assertEquals(Optional.of(at(5)), a.submittedAt());
    }

    @Test
    public void resumeAfterSubmitRejected() {
        ExamAttempt a = start(2);
        a.submit(at(5), SubmitReason.LEARNER);
        assertThrows(IllegalStateException.class, () -> a.resume(at(6)));
    }

    @Test
    public void canAnswerFalseExactlyAtSectionEnd() {
        ExamAttempt a = start(2);
        assertTrue(a.canAnswer("listening", at(30).minusMillis(1)));
        assertFalse(a.canAnswer("listening", at(30)));
        assertTrue(a.canAnswer("reading", at(30)));
    }

    @Test
    public void longGapSkipsSeveralSectionsAndSubmitsAtDeadline() {
        ExamAttempt a = ExamAttempt.start("a2", List.of(LISTENING, READING,
                new SectionPlan("writing", Duration.ofMinutes(20))), 2, T0);
        assertEquals(Optional.empty(), a.currentSection(at(1000)));
        assertEquals(AttemptStatus.SUBMITTED, a.status());
        assertEquals(Optional.of(SubmitReason.TIME_UP), a.submitReason());
        assertEquals(Optional.of(at(110)), a.submittedAt());
    }

    @Test
    public void finishEarlyAfterSectionTimedOutDoesNotSkipTwo() {
        ExamAttempt a = start(2);
        a.finishSectionEarly(at(35));
        assertEquals(AttemptStatus.IN_PROGRESS, a.status());
        assertEquals(Optional.of(READING), a.currentSection(at(36)));
        assertEquals(at(90), a.currentSectionEndsAt(at(36)));
    }

    @Test
    public void resumeAfterDeadlineAutoSubmitsTimeUpNotInterrupted() {
        ExamAttempt a = start(2);
        assertEquals(ResumeOutcome.AUTO_SUBMITTED, a.resume(at(200)));
        assertEquals(Optional.of(SubmitReason.TIME_UP), a.submitReason());
        assertFalse(a.interrupted());
        assertEquals(0, a.resumeCount());
        assertEquals(Optional.of(at(90)), a.submittedAt());
    }

    @Test
    public void resumeOverLimitAfterDeadlineIsTimeUpNotInterrupted() {
        ExamAttempt a = start(0);
        assertEquals(ResumeOutcome.AUTO_SUBMITTED, a.resume(at(200)));
        assertEquals(Optional.of(SubmitReason.TIME_UP), a.submitReason());
        assertFalse(a.interrupted());
        assertEquals(0, a.resumeCount());
    }

    @Test
    public void learnerSubmitAfterDeadlineRecordsTimeUp() {
        ExamAttempt a = start(2);
        assertFalse(a.submit(at(100), SubmitReason.LEARNER));
        assertEquals(Optional.of(SubmitReason.TIME_UP), a.submitReason());
        assertEquals(Optional.of(at(90)), a.submittedAt());
    }

    private static final Duration GRACE = Duration.ofMinutes(2);

    @Test
    public void startedAtIsAttemptStart() {
        ExamAttempt a = start(2);
        assertEquals(T0, a.startedAt());
        a.currentSection(at(45));
        assertEquals(T0, a.startedAt());
    }

    @Test
    public void lateSyncWithinGraceAfterTimeUpIsAccepted() {
        ExamAttempt a = start(2);
        Instant answered = at(90).minusSeconds(3);
        assertTrue(a.acceptsAnswer("reading", answered, at(90).plusSeconds(30), GRACE));
        assertEquals(AttemptStatus.SUBMITTED, a.status());
        assertEquals(Optional.of(SubmitReason.TIME_UP), a.submitReason());
        assertFalse(a.canAnswer("reading", at(90).plusSeconds(30)));
    }

    @Test
    public void lateSyncAfterGraceIsRejected() {
        ExamAttempt a = start(2);
        Instant answered = at(90).minusSeconds(3);
        assertFalse(a.acceptsAnswer("reading", answered, at(93), GRACE));
        assertFalse(a.acceptsAnswer("reading", answered, at(92), GRACE), "grace end is exclusive");
    }

    @Test
    public void answerRecordedAfterSectionEndIsRejected() {
        ExamAttempt a = start(2);
        assertFalse(a.acceptsAnswer("reading", at(90).plusSeconds(1), at(90).plusSeconds(30), GRACE));
        assertFalse(a.acceptsAnswer("reading", at(90), at(90).plusSeconds(30), GRACE), "end is exclusive");
        assertFalse(a.acceptsAnswer("listening", at(30), at(30).plusSeconds(10), GRACE));
        assertTrue(a.acceptsAnswer("listening", at(30).minusSeconds(1), at(30).plusSeconds(10), GRACE));
    }

    @Test
    public void answerAfterEarlyFinishIsRejected() {
        ExamAttempt a = start(2);
        a.finishSectionEarly(at(10));
        assertFalse(a.acceptsAnswer("listening", at(11), at(11), GRACE));
        assertTrue(a.acceptsAnswer("listening", at(9), at(11), GRACE));
        assertFalse(a.acceptsAnswer("listening", at(9), at(12), GRACE));
        assertTrue(a.acceptsAnswer("reading", at(11), at(11), GRACE));
        assertFalse(a.acceptsAnswer("reading", at(9), at(11), GRACE), "before the section started");
    }

    @Test
    public void answerAfterLearnerSubmitIsRejected() {
        ExamAttempt a = start(2);
        a.finishSectionEarly(at(10));
        a.submit(at(20), SubmitReason.LEARNER);
        assertTrue(a.acceptsAnswer("reading", at(19), at(21), GRACE));
        assertFalse(a.acceptsAnswer("reading", at(20), at(21), GRACE));
    }

    @Test
    public void acceptsAnswerRejectsUnknownOrUnstartedSectionsAndFutureAnswers() {
        ExamAttempt a = start(2);
        assertFalse(a.acceptsAnswer("writing", at(5), at(5), GRACE));
        assertFalse(a.acceptsAnswer("reading", at(5), at(5), GRACE), "not started yet");
        assertFalse(a.acceptsAnswer("listening", at(6), at(5), GRACE), "answeredAt after now");
        assertTrue(a.acceptsAnswer("listening", at(5), at(5), GRACE));
        assertFalse(a.acceptsAnswer("listening", T0.minusSeconds(1), at(5), GRACE), "before attempt start");
    }

    @Test
    public void acceptsAnswerValidatesArguments() {
        ExamAttempt a = start(2);
        assertThrows(IllegalArgumentException.class, () -> a.acceptsAnswer(null, at(1), at(1), GRACE));
        assertThrows(IllegalArgumentException.class, () -> a.acceptsAnswer("listening", null, at(1), GRACE));
        assertThrows(IllegalArgumentException.class, () -> a.acceptsAnswer("listening", at(1), null, GRACE));
        assertThrows(IllegalArgumentException.class, () -> a.acceptsAnswer("listening", at(1), at(1), null));
        assertThrows(IllegalArgumentException.class,
                () -> a.acceptsAnswer("listening", at(1), at(1), Duration.ofSeconds(-1)));
    }
}
