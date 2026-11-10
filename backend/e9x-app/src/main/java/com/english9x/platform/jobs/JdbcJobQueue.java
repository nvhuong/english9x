package com.english9x.platform.jobs;

import com.english9x.platform.jobs.api.JobQueue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Inserts through the application's primary {@link JdbcTemplate}, i.e. as e9x_app_login and on the caller's
 * transaction. The id is generated here and the statement has no RETURNING clause, because the application role
 * may insert jobs but not read them back.
 */
class JdbcJobQueue implements JobQueue {

    private static final String INSERT =
            "insert into jobs(id, type, payload, status, run_at) values (?, ?, ?::jsonb, 'QUEUED', ?)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    JdbcJobQueue(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    private static final String INSERT_ONCE =
            "insert into jobs(id, type, payload, status, run_at, dedupe_key) values (?, ?, ?::jsonb, 'QUEUED', ?, ?) on conflict do nothing";

    @Override
    public UUID enqueue(String type, JsonNode payload, Instant runAt) {
        JobTypes.validate(type);
        requireTransaction();
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT, id, type, json(payload), Timestamp.from(runAt == null ? clock.instant() : runAt));
        return id;
    }

    @Override
    public boolean enqueueOnce(String type, JsonNode payload, Instant runAt, String dedupeKey) {
        JobTypes.validate(type);
        if (dedupeKey == null || dedupeKey.isEmpty() || dedupeKey.length() > 200) {
            throw new IllegalArgumentException("dedupeKey must be 1 to 200 characters");
        }
        requireTransaction();
        return jdbc.update(INSERT_ONCE, UUID.randomUUID(), type, json(payload), Timestamp.from(runAt == null ? clock.instant() : runAt), dedupeKey) == 1;
    }

    private void requireTransaction() {
        // A transaction on ANOTHER resource (e.g. the worker pool's, inside a handler) would not make the insert atomic
        // with it: require one bound to the primary connection this template inserts through.
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalTransactionStateException("JobQueue.enqueue needs a transaction on the application's primary "
                    + "database connection: a job must exist only if the caller commits (a worker-role transaction does not count)");
        }
    }

    private String json(JsonNode payload) {
        try {
            return mapper.writeValueAsString(payload == null ? mapper.createObjectNode() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload cannot be serialised (" + e.getClass().getSimpleName() + ")");
        }
    }
}
