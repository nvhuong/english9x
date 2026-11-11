package com.english9x.grading.ai;

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
 * Queues the {@code ai_grade} job of a submitted attempt that has Writing or Speaking. The event is a HINT: the attempt is read back through the grader's policies and must bear it out
 * (same person, organisation and test version); the job is queued once per attempt for the end of the late-sync grace, counted from the stored submission time.
 */
@Component
class AiGradeScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(AiGradeScheduler.class);

    private final AiGradingService service;
    private final JobQueue jobs;
    private final ObjectMapper json;

    AiGradeScheduler(AiGradingService service, JobQueue jobs, ObjectMapper json) {
        this.service = service;
        this.jobs = jobs;
        this.json = json;
    }

    @ApplicationModuleListener
    void on(AttemptSubmitted event) {
        Optional<Attempt> row = service.find(event.attemptId(), event.userId(), event.orgId().orElse(null));
        if (row.isEmpty()) {
            LOG.warn("AttemptSubmitted for attempt {} names no submitted attempt of that person and organisation; no AI grading queued", event.attemptId());
            return;
        }
        Attempt a = row.get();
        if (!Objects.equals(a.testVersionId(), event.testVersionId())) {
            LOG.warn("AttemptSubmitted for attempt {} disagrees with the attempt about its test version; no AI grading queued", event.attemptId());
            return;
        }
        if (!service.hasWritingOrSpeaking(a)) {
            return;
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("attemptId", a.id().toString());
        payload.put("userId", a.userId().toString());
        if (a.orgId() != null) {
            payload.put("orgId", a.orgId().toString());
        }
        jobs.enqueueOnce(AiGradeJobHandler.TYPE, payload, service.gradeAfter(a), a.id().toString());
    }
}
