package com.english9x.platform.jobs;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Worker tuning. Retry delays and max-run are not here: they are looked up per job type (see {@link RetryPolicy}).
 *
 * @param pollInterval      how long an idle worker thread waits between polls (also the delay before its first poll)
 * @param workers           general worker threads; each claims any job type
 * @param lease             how long a claim stays valid without a heartbeat; see {@link JobStore}
 * @param heartbeatInterval how often a running job's lease is renewed; 0 (default) means one third of the lease
 * @param priorityTypes     job types that one extra dedicated thread claims exclusively (time-critical work such as
 *                          auto_submit_attempt must not wait behind a burst of slow jobs on the general threads).
 *                          The general threads still claim these types too. Empty = no dedicated thread.
 */
@ConfigurationProperties("e9x.jobs")
record JobsProperties(
        @DefaultValue("2s") Duration pollInterval,
        @DefaultValue("2") int workers,
        @DefaultValue("10m") Duration lease,
        @DefaultValue("0s") Duration heartbeatInterval,
        @DefaultValue("auto_submit_attempt") List<String> priorityTypes) {

    Duration effectiveHeartbeat() {
        return heartbeatInterval == null || heartbeatInterval.isZero() ? lease.dividedBy(3) : heartbeatInterval;
    }
}
