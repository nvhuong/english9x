package com.english9x.platform.jobs;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Worker tuning. Retry delays are not here: they are looked up per job type (see {@link RetryPolicy}).
 *
 * @param pollInterval how long an idle worker thread waits between polls (also the delay before its first poll)
 * @param workers      number of worker threads, so that a slow job (an AI call) cannot hold up a time-critical one
 * @param lease        how long a claimed job stays invisible to other workers; see {@link JobStore}
 */
@ConfigurationProperties("e9x.jobs")
record JobsProperties(
        @DefaultValue("2s") Duration pollInterval,
        @DefaultValue("2") int workers,
        @DefaultValue("10m") Duration lease) {}
