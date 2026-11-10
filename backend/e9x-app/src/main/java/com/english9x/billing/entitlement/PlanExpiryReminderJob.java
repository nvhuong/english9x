package com.english9x.billing.entitlement;

import com.english9x.billing.internal.BillingProperties;
import com.english9x.notification.api.EventType;
import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import com.english9x.platform.jobs.api.JobResult;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code plan_expiry_reminder}: tells the learner that their plan ends within {@code e9x.billing.expiry-reminder} (7 days; {@code PLAN_EXPIRING}).
 * Once per entitlement, not once per run: the dedupe key is {@code plan-expiring:<entitlementId>}, so every hourly run after the first finds the
 * notice already made.
 */
@JobHandler("plan_expiry_reminder")
class PlanExpiryReminderJob implements JobExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(PlanExpiryReminderJob.class);

    private final EntitlementNotices notices;
    private final BillingProperties props;
    private final Clock clock;

    PlanExpiryReminderJob(EntitlementNotices notices, BillingProperties props, Clock clock) {
        this.notices = notices;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public JobResult handle(Job job) {
        Instant now = clock.instant();
        int n = notices.notifyEnding(EventType.PLAN_EXPIRING, "plan-expiring:", now, now.plus(props.expiryReminder()));
        LOG.info("plan_expiry_reminder: {} entitlement(s) in the window", n);
        return JobResult.success();
    }
}
