package com.english9x.platform.jobs.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Database access as the worker login role {@code e9x_worker_login}, for {@link JobHandler} beans only. The role can
 * touch only what a migration explicitly granted it; it has no user context and does not bypass RLS.
 * Both methods throw {@link IllegalStateException} on any thread that is not currently running a job, so HTTP request
 * code can never use it.
 */
public interface WorkerJdbc {

    JdbcTemplate jdbc();

    TransactionTemplate transaction();
}
