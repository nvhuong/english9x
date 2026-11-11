package com.english9x.grading.teacher;

import com.english9x.notification.api.EventType;
import com.english9x.notification.api.NotificationService;
import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import com.english9x.platform.jobs.api.JobResult;
import com.english9x.platform.jobs.api.WorkerJdbc;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

/**
 * {@code release_expired_claim} (spec 10.3: "past the deadline the job returns it to the queue"; {@code e9x.grading.claim-timeout}): claimed items whose deadline has
 * passed go back to OPEN, through the worker's one narrow function. It also warns the holder of a claim that falls due within {@code e9x.grading.claim-due-warning}
 * ({@code GRADING_CLAIM_DUE}, once per claim: the key carries the item's version, which a new claim raises). Both look at state ("due now"), never at "since the last run".
 */
@JobHandler("release_expired_claim")
class ReleaseExpiredClaimJob implements JobExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ReleaseExpiredClaimJob.class);

    private record Due(UUID item, UUID teacher, int version) {}

    private final WorkerJdbc worker;
    private final NotificationService notifications;
    private final Clock clock;
    private final Duration warning;

    ReleaseExpiredClaimJob(WorkerJdbc worker, NotificationService notifications, Clock clock, @Value("${e9x.grading.claim-due-warning:2h}") Duration warning) {
        this.worker = worker;
        this.notifications = notifications;
        this.clock = clock;
        this.warning = warning;
    }

    @Override
    public JobResult handle(Job job) {
        Instant now = clock.instant();
        List<UUID> released = worker.transaction().execute(status -> worker.jdbc().queryForList("select r_item_id from grading_queue_release_expired(?)", UUID.class, Timestamp.from(now)));
        List<Due> due = worker.jdbc().query("select r_item_id, r_teacher_id, r_version from grading_queue_claims_due(?, ?)",
                (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3)), Timestamp.from(now), Timestamp.from(now.plus(warning)));
        for (Due d : due) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("itemId", d.item().toString());
            notifications.notify(EventType.GRADING_CLAIM_DUE, d.teacher(), data, "grading-claim-due:" + d.item() + ":" + d.version());
        }
        LOG.info("release_expired_claim: {} claim(s) returned to the queue, {} due soon", released == null ? 0 : released.size(), due.size());
        return JobResult.success();
    }
}
