package com.english9x.grading.teacher;

import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import com.english9x.platform.jobs.api.JobResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@code grading_enqueue}: puts the attempt in the teachers' queue when its grading mode says so (TEACHER, BOTH), once the late-sync grace is over. Idempotent. */
@JobHandler(EnqueueJobHandler.TYPE)
class EnqueueJobHandler implements JobExecutor {

    static final String TYPE = "grading_enqueue";

    private static final Logger LOG = LoggerFactory.getLogger(EnqueueJobHandler.class);

    private final GradingQueueService queue;

    EnqueueJobHandler(GradingQueueService queue) {
        this.queue = queue;
    }

    @Override
    public JobResult handle(Job job) {
        UUID attemptId = id(job, "attemptId");
        UUID userId = id(job, "userId");
        String org = job.payload().path("orgId").asText("");
        UUID orgId = org.isBlank() ? null : UUID.fromString(org);
        if (attemptId == null || userId == null) {
            LOG.warn("grading_enqueue {}: the payload names no attempt or person; dropped", job.id());
            return JobResult.success();
        }
        return switch (queue.enqueueByPolicy(attemptId, userId, orgId)) {
            case GRACE_NOT_OVER -> JobResult.failure("the late-sync grace of the attempt is not over");
            case NOT_ELIGIBLE -> {
                LOG.warn("grading_enqueue {}: attempt {} is not a submitted attempt of that person and organisation", job.id(), attemptId);
                yield JobResult.success();
            }
            default -> JobResult.success();
        };
    }

    private static UUID id(Job job, String field) {
        try {
            return UUID.fromString(job.payload().path(field).asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
