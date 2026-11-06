package com.english9x.platform.jobs.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Enqueues background work. Called from request paths running as the application login role.
 */
public interface JobQueue {

    /**
     * Inserts a job in the caller's transaction, so the job exists only if the caller commits. There must be an active
     * transaction ({@link org.springframework.transaction.IllegalTransactionStateException} otherwise): a job that
     * outlives a rolled-back caller would act on state that never existed.
     *
     * <p>The transaction must be bound to the application's primary database connection; a transaction on any other
     * resource (the worker role's, in a handler) is refused. Writes through {@link WorkerJdbc} and enqueueing are
     * therefore NOT atomic with each other: a handler orders them deliberately and stays idempotent (there is no outbox).
     *
     * <p>The payload may hold user data. It is readable only by the worker role and is never logged; do not put
     * secrets in it.
     *
     * @param type    job type, {@code [a-z][a-z0-9_]{0,63}}, matched against {@link JobHandler#value()}
     * @param payload JSON handed to the handler; {@code null} means an empty object
     * @param runAt   earliest start time; {@code null} means now
     * @return the job id
     */
    UUID enqueue(String type, JsonNode payload, Instant runAt);
}
