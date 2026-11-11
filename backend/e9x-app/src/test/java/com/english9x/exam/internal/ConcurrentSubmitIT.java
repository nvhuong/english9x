package com.english9x.exam.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.domain.exam.SubmitReason;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.platform.jobs.api.Job;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Review Focus #2: the learner and the auto-submit job act on one attempt at the same instant: one result, one event. */
class ConcurrentSubmitIT extends ExamIT {

    @Test
    void learnerAndJobSubmitConcurrentlyYieldsOneResult() throws Exception {
        Room room = room();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 12; round++) {
                UUID learner = learner(room);
                Instant t0 = Instant.now();
                clock.set(t0);
                UUID attempt = attemptId(startOk(room, learner));
                Job job = jobOf(attempt);
                clock.set(t0.plusSeconds(1800)); // the deadline: the job is due, and the learner clicks submit at the same moment
                CyclicBarrier go = new CyclicBarrier(2);
                Callable<Object> byLearner = () -> {
                    try (CurrentContextHolder.Scope s = CurrentContextHolder.bind(learner, room.staff().org())) {
                        go.await();
                        return service.submit(attempt);
                    }
                };
                Callable<Object> byJob = () -> {
                    go.await();
                    return autoSubmit.handle(job);
                };
                Future<Object> a = pool.submit(byLearner);
                Future<Object> b = pool.submit(byJob);
                ExamDtos.Result result = (ExamDtos.Result) a.get();
                assertTrue(((com.english9x.platform.jobs.api.JobResult) b.get()).isSuccess(), "the job is never failed by losing the race");
                assertEquals("SUBMITTED", statusOf(attempt));
                assertEquals(t0.plusSeconds(1800), result.submittedAt());
                assertEquals(1, submitted.count(attempt), "round " + round + ": exactly one AttemptSubmitted");
                assertEquals(1, su.queryForObject("select count(*) from exam_attempts where id = ? and status = 'SUBMITTED' and submitted_at is not null", Integer.class, attempt));
                // and a third and fourth call change nothing
                try (CurrentContextHolder.Scope s = CurrentContextHolder.bind(learner, room.staff().org())) {
                    assertEquals(result, service.submitAs(attempt, learner, SubmitReason.LEARNER, false));
                }
                autoSubmit.handle(job);
                assertEquals(1, submitted.count(attempt));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void jobBeforeTheDeadlineLeavesTheAttemptAlone() {
        Room room = room();
        UUID learner = learner(room);
        Instant t0 = Instant.now();
        clock.set(t0);
        UUID attempt = attemptId(startOk(room, learner));
        clock.set(t0.plusSeconds(1799));
        assertTrue(autoSubmit.handle(jobOf(attempt)).isSuccess());
        assertEquals("IN_PROGRESS", statusOf(attempt));
    }

    @Test
    void jobSubmitsAMemberWhoLeftTheOrganisation() {
        Room room = room();
        UUID learner = learner(room);
        Instant t0 = Instant.now();
        clock.set(t0);
        UUID attempt = attemptId(startOk(room, learner));
        Job job = jobOf(attempt);
        su.update("delete from org_memberships where user_id = ?", learner);
        clock.set(t0.plusSeconds(1801));
        assertTrue(autoSubmit.handle(job).isSuccess());
        assertEquals("SUBMITTED", statusOf(attempt));
        assertEquals("TIME_UP", su.queryForObject("select submit_reason from exam_attempts where id = ?", String.class, attempt));
        assertEquals(1, submitted.count(attempt));
        // the event still names the organisation the attempt was sat in
        assertEquals(room.staff().org(), submitted.all().stream().filter(e -> e.attemptId().equals(attempt)).findFirst().orElseThrow().orgId().orElseThrow());
        // a retry, a last run after the deadline, onExhausted after the work is done: all quiet
        assertTrue(autoSubmit.handle(job).isSuccess());
        autoSubmit.onExhausted(job, "lease");
        assertEquals(1, submitted.count(attempt));
    }
}
