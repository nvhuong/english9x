package com.english9x.platform.jobs;

import com.english9x.platform.jobs.api.Job;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The worker's view of the {@code jobs} table, over the worker login's own pool.
 *
 * <p><b>Claiming</b> is one auto-committed statement: {@code UPDATE ... WHERE id = (SELECT ... FOR UPDATE SKIP LOCKED
 * LIMIT 1)} marks the row RUNNING with a lease ({@code locked_until}) and a fresh {@code lease_token}, and commits.
 * No transaction is held while the handler runs, so a slow handler never holds a lock or a connection.
 *
 * <p><b>Leases and heartbeat.</b> A RUNNING job whose lease has passed is claimable again; that is how a crashed
 * worker's job comes back, and the lost run counts as an attempt (so a job that kills its worker cannot loop forever).
 * While a handler runs, the worker renews the lease every {@code e9x.jobs.heartbeat-interval} (default lease/3) by
 * lease token, so a slow but live handler is not re-claimed. The renewal stops after {@code e9x.jobs.<type>.max-run}
 * (default 30 minutes, WARN logged), so a hung handler eventually becomes reclaimable. A second concurrent run is
 * therefore still possible after a max-run overrun or a lost heartbeat; the late result of the older run is fenced off
 * by the lease token (the update matches no row) and logged. Handlers must be idempotent.
 */
class JobStore {

    static final int MAX_ERROR_LENGTH = 500;

    private static final String CLAIM = """
            WITH c AS (SELECT id, status AS prev_status FROM jobs
                        WHERE ((status = 'QUEUED' AND run_at <= ?) OR (status = 'RUNNING' AND locked_until <= ?))
                          AND (cardinality(?::text[]) = 0 OR type = ANY(?::text[]))
                        ORDER BY run_at, created_at
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED)
            UPDATE jobs j
               SET status = 'RUNNING', attempts = j.attempts + 1, locked_until = ?, lease_token = ?, updated_at = ?
              FROM c
             WHERE j.id = c.id
            RETURNING j.id, j.type, j.payload::text AS payload, j.attempts, j.created_at, c.prev_status
            """;
    private static final String RENEW = """
            UPDATE jobs SET locked_until = ?, updated_at = ?
             WHERE id = ? AND lease_token = ? AND status = 'RUNNING'
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
        return claim(now, Set.of());
    }

    /** @param onlyTypes job types to claim; empty means any type */
    Optional<ClaimedJob> claim(Instant now, Set<String> onlyTypes) {
        UUID token = UUID.randomUUID();
        Timestamp nowTs = Timestamp.from(now);
        String[] types = onlyTypes.toArray(new String[0]);
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
                        return new ClaimedJob(job, token, "RUNNING".equals(rs.getString("prev_status")));
                    } catch (JsonProcessingException e) {
                        // never include e.getMessage(): it quotes the payload
                        throw new IllegalStateException("stored payload is not valid JSON (" + e.getClass().getSimpleName() + ")");
                    }
                },
                nowTs, nowTs, types, types, Timestamp.from(now.plus(lease)), token, nowTs);
        return rows.stream().findFirst();
    }

    /** Heartbeat: pushes the lease of a running job out by one lease from {@code now}. @return false when the lease was lost */
    boolean renew(ClaimedJob c, Instant now) {
        return jdbc.update(RENEW, Timestamp.from(now.plus(lease)), Timestamp.from(now), c.job().id(), c.leaseToken()) == 1;
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
