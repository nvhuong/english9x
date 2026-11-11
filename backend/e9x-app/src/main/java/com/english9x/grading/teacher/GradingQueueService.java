package com.english9x.grading.teacher;

import com.english9x.catalog.api.GradingPolicies;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.domain.grading.GradingMode;
import com.english9x.grading.ai.AiStore;
import com.english9x.grading.ai.AiStore.Attempt;
import com.english9x.grading.ai.AiStore.Item;
import com.english9x.grading.db.GraderDatabase;
import com.english9x.notification.api.EventType;
import com.english9x.notification.api.NotificationService;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.OrgContextDeniedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Puts an attempt in the teachers' queue (spec 10.3): by policy (the grading mode of the attempt is TEACHER or BOTH and it has a Writing or Speaking part) or because the AI
 * could not take it (AI_FAILED after the retries, TEACHER_QUEUE by the emergency daily limit of P4.2). One item per attempt, whatever the number of callers (at least once is
 * fine); the SIDE (the centre's own teachers or English9x) is decided by the database ({@code grading_queue_side}), not by the caller. The ids a caller passes are hints: the
 * attempt is read back as the grader in that person's and organisation's context.
 *
 * <p>A new item tells the TEACHERs of its exam on its side ({@code GRADING_QUEUE_NEW}, once per item and teacher).
 */
@Component
public class GradingQueueService {

    public enum Reason { POLICY, AI_FAILED, AI_LIMIT }

    public enum Outcome { NOT_ELIGIBLE, GRACE_NOT_OVER, NOT_TEACHER_MODE, NOTHING_FOR_TEACHERS, QUEUED, ALREADY_QUEUED }

    private static final Logger LOG = LoggerFactory.getLogger(GradingQueueService.class);

    private final GraderDatabase grader;
    private final AiStore ai;
    private final GradingStore store;
    private final QuestionTypes types;
    private final GradingPolicies policies;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate primary;
    private final Clock clock;
    private final Duration grace;

    GradingQueueService(GraderDatabase grader, AiStore ai, GradingStore store, QuestionTypes types, GradingPolicies policies, NotificationService notifications, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, Clock clock, @Value("${e9x.exam.late-sync-grace}") Duration grace) {
        this.grader = grader;
        this.ai = ai;
        this.store = store;
        this.types = types;
        this.policies = policies;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.primary = new TransactionTemplate(transactions);
        this.clock = clock;
        this.grace = grace;
    }

    public Optional<Attempt> find(UUID attemptId, UUID userId, UUID orgId) {
        return grader.read(userId, orgId, j -> ai.attempt(j, attemptId));
    }

    public Instant enqueueAfter(Attempt a) {
        return a.submittedAt().plus(grace);
    }

    /** True when the attempt sat a Writing or Speaking section whose questions a person must grade. */
    boolean needsTeacher(Attempt a) {
        return grader.read(a.userId(), a.orgId(), j -> {
            Map<String, String> skills = ai.sectionSkills(j, a.id());
            for (Item i : ai.items(j, a.id(), a.testVersionId())) {
                if (i.questionId() != null && skills.get(i.sectionCode()) != null && !types.get(i.typeCode()).kind().autoScored()) {
                    return true;
                }
            }
            return false;
        });
    }

    /** The job of the policy: not before the late-sync grace; only for TEACHER / BOTH. */
    Outcome enqueueByPolicy(UUID attemptId, UUID userId, UUID orgId) {
        Optional<Attempt> found = find(attemptId, userId, orgId);
        if (found.isEmpty()) {
            return Outcome.NOT_ELIGIBLE;
        }
        Attempt a = found.get();
        if (clock.instant().isBefore(enqueueAfter(a))) {
            return Outcome.GRACE_NOT_OVER;
        }
        GradingMode mode = mode(a);
        if (mode == GradingMode.AI) {
            return Outcome.NOT_TEACHER_MODE;
        }
        if (!needsTeacher(a)) {
            return Outcome.NOTHING_FOR_TEACHERS;
        }
        return enqueue(a, Reason.POLICY) ? Outcome.QUEUED : Outcome.ALREADY_QUEUED;
    }

    /** For the AI (failed for good, or held back by its daily limit): the attempt goes to the teachers. Idempotent. @return true when this call queued it */
    public boolean enqueue(Attempt a, Reason reason) {
        Optional<UUID> id = grader.write(a.userId(), a.orgId(), j -> store.insertItem(j, a.id(), reason.name()));
        if (id.isEmpty()) {
            return false;
        }
        LOG.info("attempt {} is in the teachers' queue ({})", a.id(), reason);
        tellTeachers(id.get(), a.examCode());
        return true;
    }

    private void tellTeachers(UUID itemId, String examCode) {
        // a notification that fails must not undo the item: the teachers see it in the queue anyway
        try {
            for (UUID teacher : jdbc.queryForList("select r_user_id from grading_queue_teachers(?)", UUID.class, itemId)) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("examCode", examCode);
                data.put("itemId", itemId.toString());
                notifications.notify(EventType.GRADING_QUEUE_NEW, teacher, data, "grading-queue-new:" + itemId);
            }
        } catch (RuntimeException e) {
            LOG.warn("the teachers of queue item {} could not be notified: {}", itemId, e.getClass().getSimpleName());
        }
    }

    private GradingMode mode(Attempt a) {
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(a.userId(), a.orgId())) {
            return primary.execute(st -> policies.resolve(a.examCode(), Optional.ofNullable(a.orgId()), Optional.of(a.testVersionId())));
        } catch (RuntimeException e) {
            if (!isOrgDenied(e)) {
                throw e;
            }
        }
        // the member left the organisation since: the policy of the exam decides
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(a.userId(), null)) {
            return primary.execute(st -> policies.resolve(a.examCode(), Optional.empty(), Optional.of(a.testVersionId())));
        }
    }

    private static boolean isOrgDenied(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof OrgContextDeniedException) {
                return true;
            }
        }
        return false;
    }
}
