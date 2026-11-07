package com.english9x.notification.api;

/**
 * The notification catalogue of spec §13. The name is stored (notifications.event_type, notification_prefs.event_type) and shown in
 * the API, so it never changes once released. Each type needs, in this order:
 * <ol>
 *   <li>a constant here (with who it is for);</li>
 *   <li>a title and a body for BOTH languages in {@code notification/templates.vi.properties} and {@code templates.en.properties}
 *       (keys {@code NAME.title}, {@code NAME.body}; {@code {key}} is filled from the data, escaped for the channel);</li>
 *   <li>the same two strings for the in-app list in the frontend ({@code frontend/src/i18n/ns/notifications.*.json},
 *       {@code event.NAME.title / .body}, same {@code {{key}}} data).</li>
 * </ol>
 * {@code TemplatesTest} fails when a type misses a template, and the frontend i18n test when the two languages differ.
 *
 * <p>Data never carries a content title, an answer or a secret: ids, the exam code and a content type at most. The source of each
 * type is written next to it; a type whose source module does not exist yet is declared, templated and unused until that task
 * (see the dated notes in the plan) calls {@link NotificationService#notify}.
 */
public enum EventType {

    /** A new order waits for confirmation. For SYS_ADMIN. Source: billing (P5.2). */
    ORDER_PENDING("SYS_ADMIN"),
    /** The order was confirmed. For the learner or the ORG_ADMIN who ordered. Source: billing, {@code OrderConfirmed} (P5.2). */
    ORDER_CONFIRMED("LEARNER"),
    /** The order was rejected. For the learner or the ORG_ADMIN who ordered. Source: billing, {@code OrderRejected} (P5.2). */
    ORDER_REJECTED("LEARNER"),
    /** The plan ends in 7 days (e9x.billing.expiry-reminder). For the learner. Source: billing, scheduled (P5.1). */
    PLAN_EXPIRING("LEARNER"),
    /** The plan has expired. For the learner. Source: billing, scheduled (P5.1). */
    PLAN_EXPIRED("LEARNER"),
    /** A preliminary (AI) score is available. For the learner. Source: grading, {@code AttemptScored(official=false)} (P4.x). */
    SCORE_PRELIMINARY("LEARNER"),
    /** The official score is available. For the learner. Source: grading, {@code AttemptScored(official=true)} (P4.x). */
    SCORE_OFFICIAL("LEARNER"),
    /** A new submission is in the grading queue. For TEACHER. Source: grading (P4.x). */
    GRADING_QUEUE_NEW("TEACHER"),
    /** A claimed submission is about to pass its deadline. For TEACHER. Source: grading (P4.x). */
    GRADING_CLAIM_DUE("TEACHER"),
    /** Content waits for review. For the REVIEWERs of the exam. Source: workflow, {@code ReviewRequested} (wired in P1.8). */
    REVIEW_REQUESTED("REVIEWER"),
    /** The reviewer asked for changes. For the AUTHOR. Source: workflow, {@code ReviewChangesRequested} (wired in P1.8). */
    REVIEW_CHANGES_REQUESTED("AUTHOR"),
    /** The content was published. For the AUTHOR. Source: workflow, {@code ReviewApproved} (wired in P1.8). */
    CONTENT_PUBLISHED("AUTHOR"),
    /** A new assignment was given. For the learner. Source: classroom (P6.x). */
    ASSIGNMENT_NEW("LEARNER"),
    /** An assignment is due soon. For the learner. Source: classroom, scheduled (P6.x). */
    ASSIGNMENT_DUE_SOON("LEARNER"),
    /** A class session starts in an hour (e9x.notification.session-reminder). For learners and teachers. Source: classroom, scheduled (P6.x). */
    SESSION_REMINDER("LEARNER"),
    /** AI grading failed for good. For SYS_ADMIN. Source: grading, {@code onExhausted} of the ai_grade job (P4.x). */
    AI_GRADING_FAILED("SYS_ADMIN");

    private final String audience;

    EventType(String audience) {
        this.audience = audience;
    }

    /** Who the type is for (a role name, or LEARNER): a hint for the preferences screen, not an access rule. */
    public String audience() {
        return audience;
    }
}
