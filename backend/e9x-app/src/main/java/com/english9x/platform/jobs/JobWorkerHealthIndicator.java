package com.english9x.platform.jobs;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** DOWN when the worker is meant to be running but its polling threads are gone. Absent when the worker is disabled. */
class JobWorkerHealthIndicator implements HealthIndicator {

    private final JobWorker worker;

    JobWorkerHealthIndicator(JobWorker worker) {
        this.worker = worker;
    }

    @Override
    public Health health() {
        int live = worker.liveThreads();
        int expected = worker.expectedThreads();
        Health.Builder b = !worker.isRunning() ? Health.outOfService() : live < expected ? Health.down() : Health.up();
        return b.withDetail("threads", live).withDetail("expected", expected).build();
    }
}
