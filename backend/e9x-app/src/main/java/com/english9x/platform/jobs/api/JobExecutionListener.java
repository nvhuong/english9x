package com.english9x.platform.jobs.api;

/**
 * Hook run on the worker thread after every handler run (returned or threw), before the result is recorded. A bean
 * implementing it can restore thread state a handler may have leaked (e.g. a bound user context) so that the next job,
 * possibly of another tenant, starts clean. Exceptions it throws are logged and ignored.
 */
public interface JobExecutionListener {

    void afterJob(Job job);
}
