package com.english9x.grading.ai;

import com.english9x.grading.ai.AiStore.Attempt;
import com.english9x.grading.ai.api.AiGradingException;
import com.english9x.grading.db.GraderDatabase;
import com.english9x.notification.api.EventType;
import com.english9x.notification.api.NotificationService;
import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import com.english9x.platform.jobs.api.JobResult;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code ai_grade}: queued by {@link AiGradeScheduler} for the end of the late-sync grace. The payload is a hint like an event; {@link AiGradingService} reads the attempt back.
 * A failure (Gemini down, a schema the model cannot meet, a skill missing) is returned as a failed result, so the queue retries after the delays of
 * {@code e9x.grading.ai.retry-delays}; its reason names the exception and its root cause (class names and our own messages only: never the learner's words).
 *
 * <p>{@link #onExhausted} is the terminal state: AI_FAILED plus an alert to every SYS_ADMIN. It is fast (two small statements, no model call), idempotent (the state is written
 * once, the notification has a dedupe key) and does nothing for work that is done: the queue also calls it when the lease of the last attempt expired, and a run that overran its
 * deadline may have finished meanwhile.
 */
@JobHandler(AiGradeJobHandler.TYPE)
class AiGradeJobHandler implements JobExecutor {

    static final String TYPE = "ai_grade";

    private static final Logger LOG = LoggerFactory.getLogger(AiGradeJobHandler.class);

    private final AiGradingService service;
    private final GraderDatabase grader;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final AiStore store;

    AiGradeJobHandler(AiGradingService service, GraderDatabase grader, NotificationService notifications, JdbcTemplate jdbc, AiStore store) {
        this.service = service;
        this.grader = grader;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.store = store;
    }

    @Override
    public JobResult handle(Job job) {
        UUID attemptId = id(job, "attemptId");
        UUID userId = id(job, "userId");
        String org = job.payload().path("orgId").asText("");
        UUID orgId = org.isBlank() ? null : UUID.fromString(org);
        if (attemptId == null || userId == null) {
            LOG.warn("ai_grade {}: the payload names no attempt or person; dropped", job.id());
            return JobResult.success();
        }
        try {
            return switch (service.grade(attemptId, userId, orgId)) {
                case NOT_ELIGIBLE -> {
                    LOG.warn("ai_grade {}: attempt {} is not a submitted attempt of that person and organisation; nothing graded", job.id(), attemptId);
                    yield JobResult.success();
                }
                case GRACE_NOT_OVER -> JobResult.failure("the late-sync grace of the attempt is not over");
                case NOT_AI, TEACHER_QUEUE, DONE, ALREADY_DONE -> JobResult.success();
            };
        } catch (AiGradingException e) {
            if (e.kind() == AiGradingException.Kind.PERMANENT) {
                // trying again will not help (no skill, a refusal, no key): straight to AI_FAILED, no retry schedule burnt
                giveUp(job, describe(e));
                return JobResult.success();
            }
            return JobResult.failure(describe(e));
        } catch (Exception e) {
            return JobResult.failure(describe(e));
        }
    }

    @Override
    public void onExhausted(Job job, String reason) {
        giveUp(job, reason);
    }

    private void giveUp(Job job, String reason) {
        UUID attemptId = id(job, "attemptId");
        UUID userId = id(job, "userId");
        if (attemptId == null || userId == null) {
            return;
        }
        String org = job.payload().path("orgId").asText("");
        UUID orgId = org.isBlank() ? null : UUID.fromString(org);
        Optional<Attempt> attempt = service.find(attemptId, userId, orgId);
        if (attempt.isEmpty()) {
            LOG.error("ai_grade {} gave up on an attempt that is not there: {}", job.id(), reason);
            return;
        }
        Attempt a = attempt.get();
        Optional<String> state = grader.read(a.userId(), a.orgId(), j -> store.state(j, a.id()));
        if (state.isPresent() && !"AI_FAILED".equals(state.get())) {
            LOG.info("ai_grade {}: attempt {} is already {}; nothing to give up", job.id(), a.id(), state.get());
            return; // the last run was late but finished, or the teachers have it
        }
        record(job, a, reason);
    }

    /** Writes AI_FAILED and alerts the administrators, only if this call really wrote it (the state is read before, so a late success may have won the race). */
    void record(Job job, Attempt a, String reason) {
        boolean recorded = Boolean.TRUE.equals(grader.write(a.userId(), a.orgId(), j -> store.markFailed(j, a, reason)));
        if (!recorded) {
            LOG.info("ai_grade {}: attempt {} got a state while giving up (a late run finished); no alert", job.id(), a.id());
            return; // never a false alert: markFailed is a no-op when DONE / TEACHER_QUEUE was written between the read and the write
        }
        LOG.error("ai_grade {} for attempt {} gave up: {}", job.id(), a.id(), reason);
        for (UUID admin : jdbc.queryForList("select r_user_id from notification_sys_admins()", UUID.class)) {
            notifications.notify(EventType.AI_GRADING_FAILED, admin, Map.of("attemptId", a.id().toString()), "ai-grading-failed:" + a.id());
        }
    }

    /** "AiGradingException(TRANSIENT): Gemini answered HTTP 500 <- ConnectException": our own message, then the class of the root cause. */
    static String describe(Throwable e) {
        StringBuilder out = new StringBuilder(e.getClass().getSimpleName());
        if (e instanceof AiGradingException g) {
            out.append('(').append(g.kind()).append("): ").append(g.getMessage());
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root != e) {
            out.append(" <- ").append(root.getClass().getSimpleName());
        }
        return out.toString();
    }

    private static UUID id(Job job, String field) {
        try {
            return UUID.fromString(job.payload().path(field).asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
