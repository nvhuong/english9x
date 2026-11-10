package com.english9x.billing.plan;

import com.english9x.billing.plan.PlanDtos.PlanRequest;
import com.english9x.billing.plan.PlanDtos.PlanView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Every statement runs under the caller's Row-Level Security: a learner sees active plans only, a real SYS_ADMIN all and the only writer. */
@Repository
class PlanRepository {

    private static final RowMapper<PlanView> MAP = (rs, i) -> new PlanView(rs.getObject("id", UUID.class), rs.getString("name"),
            rs.getLong("price_vnd"), rs.getInt("months"), rs.getInt("max_courses"), rs.getBoolean("active"));
    private static final String COLUMNS = "id, name, price_vnd, months, max_courses, active";

    private final JdbcTemplate jdbc;

    PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<PlanView> listActive() {
        return jdbc.query("select " + COLUMNS + " from plans where active order by price_vnd, name, id", MAP);
    }

    List<PlanView> listAll() {
        return jdbc.query("select " + COLUMNS + " from plans order by active desc, price_vnd, name, id", MAP);
    }

    Optional<PlanView> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from plans where id = ?", MAP, id).stream().findFirst();
    }

    PlanView insert(PlanRequest r) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into plans(id, name, price_vnd, months, max_courses, active) values (?, ?, ?, ?, ?, ?)",
                id, r.name().strip(), r.priceVnd(), r.months(), r.maxCourses(), r.active());
        return find(id).orElseThrow();
    }

    Optional<PlanView> update(UUID id, PlanRequest r) {
        int n = jdbc.update("update plans set name = ?, price_vnd = ?, months = ?, max_courses = ?, active = ?, updated_at = now() where id = ?",
                r.name().strip(), r.priceVnd(), r.months(), r.maxCourses(), r.active(), id);
        return n == 0 ? Optional.empty() : find(id);
    }
}
