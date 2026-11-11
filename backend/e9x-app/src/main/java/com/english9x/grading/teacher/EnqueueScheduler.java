package com.english9x.grading.teacher;

import com.english9x.exam.api.events.AttemptSubmitted;
import com.english9x.grading.ai.AiStore.Attempt;
import com.english9x.platform.jobs.api.JobQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Queues the {@code grading_enqueue} job of a submitted attempt for the end of the late-sync grace, like {@code ai_grade}: the teachers must not open an answer that may still
 * be changed by a late sync. The event is a HINT: the attempt is read back through the grader's policies and must bear it out. Whether the attempt really goes to the teachers
 * (the mode of its policy, a Writing / Speaking part) is decided by the job, once the grace is over.
 */
@Component
class EnqueueScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(EnqueueScheduler.class);

    private final GradingQueueService queue;
    private final JobQueue jobs;
    private final ObjectMapper json;

    EnqueueScheduler(GradingQueueService queue, JobQueue jobs, ObjectMapper json) {
        this.queue = queue;
        this.jobs = jobs;
        this.json = json;
    }

    @ApplicationModuleListener
    void on(AttemptSubmitted event) {
        Optional<Attempt> row = queue.find(event.attemptId(), event.userId(), event.orgId().orElse(null));
        if (row.isEmpty()) {
            LOG.warn("AttemptSubmitted for attempt {} names no submitted attempt of that person and organisation; nothing queued for teachers", event.attemptId());
            return;
        }
        Attempt a = row.get();
        if (!Objects.equals(a.testVersionId(), event.testVersionId())) {
            LOG.warn("AttemptSubmitted for attempt {} disagrees with the attempt about its test version; nothing queued for teachers", event.attemptId());
            return;
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("attemptId", a.id().toString());
        payload.put("userId", a.userId().toString());
        if (a.orgId() != null) {
            payload.put("orgId", a.orgId().toString());
        }
        jobs.enqueueOnce(EnqueueJobHandler.TYPE, payload, queue.enqueueAfter(a), a.id().toString());
    }
}
