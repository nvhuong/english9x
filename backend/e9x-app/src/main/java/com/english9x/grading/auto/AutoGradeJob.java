package com.english9x.grading.auto;

import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import com.english9x.platform.jobs.api.JobResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code grade_attempt}: queued by {@link AttemptSubmittedListener} for the instant the late-sync grace of the attempt is over, and run once the worker
 * finds it due. A job payload is a hint like an event ({@code jobs} is writable by the request role), so {@link AutoGrader} reads the attempt back before it
 * does anything: a forged job finds no attempt and ends as a no-op, one that is early (a forged {@code run_at}) is refused until the grace is over and
 * then does exactly what the genuine one would. At least once, idempotent: a repeated run writes no score and announces nothing twice.
 */
@JobHandler(AutoGradeJob.TYPE)
class AutoGradeJob implements JobExecutor {

    static final String TYPE = "grade_attempt";

    private static final Logger LOG = LoggerFactory.getLogger(AutoGradeJob.class);

    private final AutoGrader grader;

    AutoGradeJob(AutoGrader grader) {
        this.grader = grader;
    }

    @Override
    public JobResult handle(Job job) {
        UUID attemptId = id(job, "attemptId");
        UUID userId = id(job, "userId");
        String org = job.payload().path("orgId").asText("");
        UUID orgId = org.isBlank() ? null : UUID.fromString(org);
        if (attemptId == null || userId == null) {
            LOG.warn("grade_attempt {}: the payload names no attempt or person; dropped", job.id());
            return JobResult.success();
        }
        return switch (grader.grade(attemptId, userId, orgId)) {
            case NOT_ELIGIBLE -> {
                LOG.warn("grade_attempt {}: attempt {} is not a submitted attempt of that person and organisation; nothing scored", job.id(), attemptId);
                yield JobResult.success();
            }
            case GRACE_NOT_OVER -> JobResult.failure("the late-sync grace of the attempt is not over");
            case GRADED, ALREADY_GRADED -> JobResult.success();
        };
    }

    @Override
    public void onExhausted(Job job, String reason) {
        LOG.error("grade_attempt {} for attempt {} gave up: {}", job.id(), job.payload().path("attemptId").asText(), reason);
    }

    private static UUID id(Job job, String field) {
        try {
            return UUID.fromString(job.payload().path(field).asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
