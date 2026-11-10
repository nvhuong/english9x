package com.english9x.billing.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The learning-access gate (spec section 5, Review Focus #5). Consumed by the lesson gate (P3.1) and by the exam module when it starts or
 * continues an attempt (P3.3). Nothing else decides whether a learner may learn: content is open to a person who holds an entitlement that
 * is valid for the course, whatever the source (a paid order, an organisation's seat).
 *
 * <p>Reads {@code entitlements} under the caller's Row-Level Security, i.e. as the person bound to the current thread
 * ({@code CurrentContextHolder}): a person sees their own entitlements, so asking about somebody else answers {@code false}, and with nobody
 * bound the answer is {@code false} (fail closed). Call it inside the request (or after binding the person); it opens a read-only
 * transaction when the caller has none.
 *
 * <p>The gate is about starting NEW learning; results already earned are never gated here: an expired learner still reads their old
 * results (F41).
 */
public interface Entitlements {

    /**
     * Whether {@code userId} may open new content of the course at {@code now}: some entitlement is valid then
     * ({@code validFrom <= now < validUntil}). Use it to open a lesson, to start an attempt, to start a new test.
     */
    boolean activeFor(UUID userId, UUID courseId, Instant now);

    /**
     * Whether an attempt that began at {@code attemptStartedAt} may go on (answer, save, submit): true if the learner was entitled when it
     * began, even when the entitlement has ended since. A learner who is half-way through an exam when the plan runs out finishes that
     * attempt and gets no new one ({@link #activeFor} is false). An attempt that began without an entitlement cannot continue.
     */
    boolean canContinueAttempt(UUID userId, UUID courseId, Instant attemptStartedAt);
}
