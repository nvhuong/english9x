package com.english9x.content.importer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** import_jobs, import_errors and import_job_versions as the application role, in the caller's transaction: row-level security decides what is there. */
@Repository
class ImportRepository {

    /** The most errors kept for one import; the job stops collecting there and says so. */
    static final int MAX_ERRORS = 1000;

    record Job(UUID id, UUID ownerOrgId, UUID userId, String examCode, String typeCode, String status, String sourceName, boolean hasMedia, int totalRows,
               int processedRows, int errorCount, int createdCount, String failureReason, boolean hasReport, Instant createdAt, Instant finishedAt) {

        boolean finished() {
            return !status.equals("QUEUED") && !status.equals("RUNNING");
        }
    }

    private static final String COLUMNS = "id, owner_org_id, user_id, exam_code, type_code, status, source_name, has_media, total_rows, processed_rows, "
            + "error_count, created_count, failure_reason, has_report, created_at, finished_at";

    private static final RowMapper<Job> JOB = (rs, n) -> new Job(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12),
            rs.getString(13), rs.getBoolean(14), instant(rs.getTimestamp(15)), instant(rs.getTimestamp(16)));

    private final JdbcTemplate jdbc;

    ImportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    void insert(UUID id, UUID ownerOrgId, UUID userId, String examCode, String typeCode, String sourceName, boolean hasMedia) {
        jdbc.update("insert into import_jobs(id, owner_org_id, user_id, exam_code, type_code, source_name, has_media) values (?,?,?,?,?,?,?)",
                id, ownerOrgId, userId, examCode, typeCode, sourceName, hasMedia);
    }

    Optional<Job> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from import_jobs where id = ?", JOB, id).stream().findFirst();
    }

    List<Job> recent(int limit) {
        return jdbc.query("select " + COLUMNS + " from import_jobs order by created_at desc, id limit ?", JOB, limit);
    }

    List<RowError> errors(UUID id) {
        return jdbc.query("select row_no, column_name, message from import_errors where import_job_id = ? order by row_no, id",
                (rs, n) -> new RowError(rs.getInt(1), rs.getString(2), rs.getString(3)), id);
    }

    List<UUID> versionIds(UUID id) {
        return jdbc.queryForList("select question_version_id from import_job_versions where import_job_id = ? order by position", UUID.class, id);
    }

    /** QUEUED or RUNNING to RUNNING; false when the import is already finished (a re-run of a job that had completed). */
    boolean markRunning(UUID id) {
        return jdbc.update("update import_jobs set status = 'RUNNING', updated_at = now() where id = ? and status in ('QUEUED', 'RUNNING')", id) == 1;
    }

    void progress(UUID id, int total, int processed) {
        jdbc.update("update import_jobs set total_rows = ?, processed_rows = ?, updated_at = now() where id = ? and status = 'RUNNING'", total, processed, id);
    }

    void addVersion(UUID id, int position, int row, UUID versionId) {
        jdbc.update("insert into import_job_versions(import_job_id, position, row_no, question_version_id) values (?,?,?,?)", id, position, row, versionId);
    }

    /** @return false when the import is no longer RUNNING (marked FAILED meanwhile): the caller must then roll back what it made */
    boolean succeed(UUID id, int created) {
        return jdbc.update("update import_jobs set status = 'SUCCEEDED', created_count = ?, processed_rows = total_rows, updated_at = now(), finished_at = now() "
                + "where id = ? and status = 'RUNNING'", created, id) == 1;
    }

    boolean reject(UUID id, List<RowError> errors, boolean hasReport) {
        List<RowError> kept = errors.size() > MAX_ERRORS ? errors.subList(0, MAX_ERRORS) : errors;
        if (jdbc.update("update import_jobs set status = 'REJECTED', error_count = ?, created_count = 0, has_report = ?, updated_at = now(), finished_at = now() "
                + "where id = ? and status = 'RUNNING'", errors.size(), hasReport, id) != 1) {
            return false;
        }
        jdbc.batchUpdate("insert into import_errors(import_job_id, row_no, column_name, message) values (?,?,?,?)", kept, 200,
                (ps, e) -> {
                    ps.setObject(1, id);
                    ps.setInt(2, e.row());
                    ps.setString(3, e.column());
                    ps.setString(4, e.message().length() > 1000 ? e.message().substring(0, 1000) : e.message());
                });
        return true;
    }
}
