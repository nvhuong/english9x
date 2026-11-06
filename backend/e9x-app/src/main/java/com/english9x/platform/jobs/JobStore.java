package com.english9x.platform.jobs;

import com.english9x.platform.jobs.api.Job;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The worker's view of the {@code jobs} table, over the worker login's own pool.
 *
 * <p><b>Claiming</b> is one auto-committed statement: {@code UPDATE ... WHERE id = (SELECT ... FOR UPDATE SKIP LOCKED
 * LIMIT 1)} marks the row RUNNING with a lease ({@code locked_until}) and a fresh {@code lease_token}, and commits.
 * No transaction is held while the handler runs, so a slow handler never holds a lock or a connection.
 *
 * <p><b>Leases.</b> A RUNNING job whose lease has passed is claimable again; that is how a crashed worker's job comes
 * back, and the lost run counts as an attempt (so a job that kills its worker cannot loop forever). There is no
 * heartbeat: the lease ({@code e9x.jobs.lease}, default 10 minutes) is far above the longest handler the spec plans
 * (a 60 s Gemini call, an Excel import of at most 2000 rows). A handler that nevertheless outruns its lease is
 * detected, not silent: its late result is fenced off by the lease token (the update matches no row), the worker logs
 * a warning, and the newer run owns the job. Handlers must be idempotent either way.
 */
class JobStore {

    static final int MAX_ERROR_LENGTH = 500;

    private static final String CLAIM = """
            UPDATE jobs
               SET status = 'RUNNING', attempts = attempts + 1, locked_until = ?, lease_token = ?, updated_at = ?
             WHERE id = (SELECT id FROM jobs
                          WHERE (status = 'QUEUED' AND run_at <= ?) OR (status = 'RUNNING' AND locked_until <= ?)
                          ORDER BY run_at, created_at
                          LIMIT 1
                          FOR UPDATE SKIP LOCKED)
            RETURNING id, type, payload::text AS payload, attempts, created_at
            """;
    private static final String SUCCEED = """
            UPDATE jobs SET status = 'SUCCEEDED', locked_until = NULL, lease_token = NULL, finished_at = ?, updated_at = ?
             WHERE id = ? AND lease_token = ? AND status = 'RUNNING'
            """;
    private static final String RETRY = """
            UPDATE jobs SET status = 'QUEUED', run_at = ?, last_error = ?, locked_until = NULL, lease_token = NULL, updated_at = ?
             WHERE id = ? AND lease_token = ? AND status = 'RUNNING'
            """;
    private static final String FAIL = """
            UPDATE jobs SET status = 'FAILED', last_error = ?, locked_until = NULL, lease_token = NULL, finished_at = ?, updated_at = ?
             WHERE id = ? AND lease_token = ? AND status = 'RUNNING'
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Duration lease;

    JobStore(JdbcTemplate worker, ObjectMapper mapper, Duration lease) {
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("e9x.jobs.lease must be positive");
        }
        this.jdbc = worker;
        this.mapper = mapper;
        this.lease = lease;
    }

    Optional<ClaimedJob> claim(Instant now) {
        UUID token = UUID.randomUUID();
        Timestamp nowTs = Timestamp.from(now);
        List<ClaimedJob> rows = jdbc.query(
                CLAIM,
                (rs, i) -> {
                    try {
                        Job job = new Job(
                                rs.getObject("id", UUID.class),
                                rs.getString("type"),
                                mapper.readTree(rs.getString("payload")),
                                rs.getInt("attempts"),
                                rs.getTimestamp("created_at").toInstant());
                        return new ClaimedJob(job, token);
                    } catch (JsonProcessingException e) {
                        // never include e.getMessage(): it quotes the payload
                        throw new IllegalStateException("stored payload is not valid JSON (" + e.getClass().getSimpleName() + ")");
                    }
                },
                Timestamp.from(now.plus(lease)), token, nowTs, nowTs, nowTs);
        return rows.stream().findFirst();
    }

    /** @return false when the lease was lost (another claim took the job): the result is discarded */
    boolean succeed(ClaimedJob c, Instant now) {
        Timestamp ts = Timestamp.from(now);
        return jdbc.update(SUCCEED, ts, ts, c.job().id(), c.leaseToken()) == 1;
    }

    boolean retryAt(ClaimedJob c, Instant runAt, String error, Instant now) {
        return jdbc.update(RETRY, Timestamp.from(runAt), limit(error), Timestamp.from(now), c.job().id(), c.leaseToken()) == 1;
    }

    boolean fail(ClaimedJob c, String error, Instant now) {
        Timestamp ts = Timestamp.from(now);
        return jdbc.update(FAIL, limit(error), ts, ts, c.job().id(), c.leaseToken()) == 1;
    }

    /** Error text is stored cut to {@value #MAX_ERROR_LENGTH} characters, on one line. */
    static String limit(String error) {
        String s = error == null ? "failed" : error.replaceAll("[\\r\\n\\t]+", " ");
        return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH);
    }
}
