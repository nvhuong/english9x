package com.english9x.platform.context;

import com.english9x.org.api.CurrentContextHolder;
import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lives in platform (not org) because org would otherwise depend on platform while platform.web already depends on org.
 * After every job: a handler that bound a user/org context and did not close the scope would otherwise leave it on the
 * worker thread, and the next job (maybe of another tenant) would run under it. Clears it and logs an error without
 * any user or org id.
 */
@Component
class LeakedBindingCleaner implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(LeakedBindingCleaner.class);

    @Override
    public void afterJob(Job job) {
        if (CurrentContextHolder.current().isPresent()) {
            log.error("job {} type={} left a user/org context bound on the worker thread; clearing it", job.id(), job.type());
            CurrentContextHolder.clear();
        }
    }
}
