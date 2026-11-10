package com.english9x.billing.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Spec section 15.1, {@code e9x.billing.*}.
 *
 * @param orderExpiry          a PENDING_PAYMENT order older than this becomes EXPIRED (job expire_orders)
 * @param transferNoteMaxLength longest transfer note; over it the note is the order code alone
 * @param expiryReminder       how long before a plan ends the learner is told (job plan_expiry_reminder)
 * @param jobInterval          period of the three billing jobs (their tick is a time bucket of this length)
 * @param expiredNoticeWindow  expire_entitlements tells the learners whose plan ended within this long; an older end is never announced
 *                             (the window bounds the work of every run and covers an outage of the job)
 */
@ConfigurationProperties("e9x.billing")
public record BillingProperties(
        @DefaultValue("7d") Duration orderExpiry,
        @DefaultValue("50") int transferNoteMaxLength,
        @DefaultValue("7d") Duration expiryReminder,
        @DefaultValue("1h") Duration jobInterval,
        @DefaultValue("14d") Duration expiredNoticeWindow) {}
