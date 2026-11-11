package com.english9x.grading.auto;

import com.english9x.exam.api.events.AttemptSubmitted;
import com.english9x.grading.auto.GraderStore.Attempt;
import com.english9x.platform.jobs.api.JobQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Scoring starts here: after the exam module's transaction committed, on a pool thread with no user bound. The event is a HINT (the request role can write
 * {@code event_publication}, so it can forge one), therefore the listener first reads the attempt back through the grader's policies and checks that the row
 * and the event agree; an event with no such attempt, or one that disagrees, is dropped (logged by id) and queues nothing.
 *
 * <p>It then queues the scoring for the moment the late-sync grace is over, counted from the submission time of the ROW, not the event's. Queued once per
 * attempt ({@code enqueueOnce}), so a redelivered event adds nothing; the job is the one that scores ({@link AutoGradeJob}). The method runs in the
 * transaction {@code @ApplicationModuleListener} opens, which is what {@code JobQueue} requires.
 */
@Component
class AttemptSubmittedListener {

    private static final Logger LOG = LoggerFactory.getLogger(AttemptSubmittedListener.class);

    private final AutoGrader grader;
    private final JobQueue jobs;
    private final ObjectMapper json;

    AttemptSubmittedListener(AutoGrader grader, JobQueue jobs, ObjectMapper json) {
        this.grader = grader;
        this.jobs = jobs;
        this.json = json;
    }

    @ApplicationModuleListener
    void on(AttemptSubmitted event) {
        UUID orgId = event.orgId().orElse(null);
        Optional<Attempt> row = grader.find(event.attemptId(), event.userId(), orgId);
        if (row.isEmpty()) {
            LOG.warn("AttemptSubmitted for attempt {} names no submitted attempt of that person and organisation; dropped", event.attemptId());
            return;
        }
        Attempt a = row.get();
        if (!Objects.equals(a.testVersionId(), event.testVersionId())) {
            LOG.warn("AttemptSubmitted for attempt {} disagrees with the attempt about its test version; dropped", event.attemptId());
            return;
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("attemptId", a.id().toString());
        payload.put("userId", a.userId().toString());
        if (a.orgId() != null) {
            payload.put("orgId", a.orgId().toString());
        }
        Instant due = grader.gradeAfter(a);
        jobs.enqueueOnce(AutoGradeJob.TYPE, payload, due, a.id().toString());
    }
}
