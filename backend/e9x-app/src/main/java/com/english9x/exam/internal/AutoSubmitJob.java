package com.english9x.exam.internal;

import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.OrgContextDeniedException;
import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import com.english9x.platform.jobs.api.JobResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code auto_submit_attempt}: queued for the deadline of an attempt (and again whenever early finishes bring the deadline forward). Runs as the person
 * with their organisation bound, like a request, so Row-Level Security does the reading and writing; the domain decides whether the time is up.
 *
 * <ul>
 *   <li>Idempotent and race-safe: a submitted attempt is a no-op; against a learner's own submission exactly one wins the version check, the other reloads
 *       and finds SUBMITTED (one result, one {@code AttemptSubmitted}).
 *   <li>A member who LEFT the organisation cannot be bound in it ({@link OrgContextDeniedException} when the transaction starts): the attempt is then
 *       submitted in the personal context. Their attempt policy has no organisation clause for the owner, so this works; the job never fails for it.
 *   <li>A run that finishes after its deadline, a retry after the work was done, a lease lost mid-way: all end as "already submitted" and succeed, so a job
 *       is never left FAILED for work that is done. {@link #onExhausted} makes one last attempt through both contexts and only logs (the attempt id only).
 * </ul>
 */
@JobHandler(ExamService.AUTO_SUBMIT)
class AutoSubmitJob implements JobExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(AutoSubmitJob.class);

    private final ExamService service;

    AutoSubmitJob(ExamService service) {
        this.service = service;
    }

    @Override
    public JobResult handle(Job job) {
        run(job);
        return JobResult.success();
    }

    @Override
    public void onExhausted(Job job, String reason) {
        try {
            run(job);
        } catch (RuntimeException e) {
            LOG.error("auto_submit_attempt {} could not submit attempt {}: {}", job.id(), job.payload().path("attemptId").asText(), e.getClass().getSimpleName());
        }
    }

    private void run(Job job) {
        UUID attemptId = UUID.fromString(job.payload().path("attemptId").asText());
        UUID userId = UUID.fromString(job.payload().path("userId").asText());
        String org = job.payload().path("orgId").asText("");
        UUID orgId = org.isBlank() ? null : UUID.fromString(org);
        try {
            submitIn(attemptId, userId, orgId);
        } catch (RuntimeException e) {
            if (orgId == null || !isOrgDenied(e)) {
                throw e;
            }
            LOG.info("auto_submit_attempt: the member left the organisation; submitting attempt {} in the personal context", attemptId);
            submitIn(attemptId, userId, null);
        }
    }

    private void submitIn(UUID attemptId, UUID userId, UUID orgId) {
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(userId, orgId)) {
            service.autoSubmit(attemptId, userId);
        }
    }

    static boolean isOrgDenied(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof OrgContextDeniedException) {
                return true;
            }
        }
        return false;
    }
}
