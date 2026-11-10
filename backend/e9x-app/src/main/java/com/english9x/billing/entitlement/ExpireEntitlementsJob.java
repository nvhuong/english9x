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
 * {@code expire_entitlements}: tells the learner that their plan has ended (spec section 11 / 13, {@code PLAN_EXPIRED}). An entitlement carries
 * its own end ({@code valid_until}) and the gate compares it with the clock, so an entitlement needs no "expired" marker to stop working; this job
 * only announces. It covers the entitlements that ended within {@code e9x.billing.expired-notice-window} before now, once each (dedupe key
 * {@code plan-expired:<entitlementId>}); an end older than the window is never announced.
 */
@JobHandler("expire_entitlements")
class ExpireEntitlementsJob implements JobExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ExpireEntitlementsJob.class);

    private final EntitlementNotices notices;
    private final BillingProperties props;
    private final Clock clock;

    ExpireEntitlementsJob(EntitlementNotices notices, BillingProperties props, Clock clock) {
        this.notices = notices;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public JobResult handle(Job job) {
        Instant now = clock.instant();
        int n = notices.notifyEnding(EventType.PLAN_EXPIRED, "plan-expired:", now.minus(props.expiredNoticeWindow()), now);
        LOG.info("expire_entitlements: {} entitlement(s) in the window", n);
        return JobResult.success();
    }
}
