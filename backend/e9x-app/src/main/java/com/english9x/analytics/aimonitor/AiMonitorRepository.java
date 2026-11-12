package com.english9x.analytics.aimonitor;

import com.english9x.analytics.aimonitor.AiMonitorDtos.Deviation;
import com.english9x.analytics.aimonitor.AiMonitorDtos.FailedJob;
import com.english9x.analytics.aimonitor.AiMonitorDtos.PersonCost;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The monitor's reads: SECURITY DEFINER functions of V202611160002 that answer nothing unless the caller is the system administrator. */
@Repository
class AiMonitorRepository {

    private final JdbcTemplate jdbc;

    AiMonitorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<PersonCost> cost(Instant from, Instant to) {
        return jdbc.query("select * from monitor_ai_cost(?, ?)", (rs, n) -> new PersonCost(rs.getDate("day").toLocalDate(), rs.getObject("user_id", UUID.class), rs.getString("email"),
                rs.getBigDecimal("cost_vnd"), rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getInt("evaluations")), Timestamp.from(from), Timestamp.from(to));
    }

    List<FailedJob> failedJobs(Instant from, Instant to, int limit) {
        return jdbc.query("select * from monitor_failed_jobs(?, ?, ?)", (rs, n) -> new FailedJob(rs.getObject("id", UUID.class), rs.getString("type"), rs.getInt("attempts"),
                rs.getString("last_error"), rs.getTimestamp("failed_at").toInstant()), Timestamp.from(from), Timestamp.from(to), limit);
    }

    int aiFailures(Instant from, Instant to) {
        Integer n = jdbc.queryForObject("select monitor_ai_failures(?, ?)", Integer.class, Timestamp.from(from), Timestamp.from(to));
        return n == null ? 0 : n;
    }

    List<Deviation> deviation(Instant from, Instant to) {
        return jdbc.query("select * from monitor_ai_deviation(?, ?)", (rs, n) -> new Deviation(rs.getString("type_code"), rs.getString("type_name"), rs.getInt("samples"),
                rs.getBigDecimal("mean_deviation"), rs.getBigDecimal("mean_abs_deviation")), Timestamp.from(from), Timestamp.from(to));
    }
}
