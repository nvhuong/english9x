package com.english9x.billing.order;

import com.english9x.billing.order.OrderDtos.OrderCourse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Every statement runs under the caller's Row-Level Security. Reads add {@code user_id = ?} as well: a SYS_ADMIN also sees every order, but not here. */
@Repository
class OrderRepository {

    record PlanRow(UUID id, long priceVnd, int months, int maxCourses, boolean active) {}

    record OrderRow(UUID id, String code, String status, long amountVnd, UUID planId, int planMonths, Instant createdAt, String rejectReason) {}

    private final JdbcTemplate jdbc;

    OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<PlanRow> findPlan(UUID id) {
        return jdbc.query("select id, price_vnd, months, max_courses, active from plans where id = ?",
                (rs, i) -> new PlanRow(rs.getObject("id", UUID.class), rs.getLong("price_vnd"), rs.getInt("months"), rs.getInt("max_courses"), rs.getBoolean("active")),
                id).stream().findFirst();
    }

    /** The courses among {@code ids} an order may select: English9x's own (no owner organisation) and PUBLISHED. In the order of {@code ids}. */
    List<OrderCourse> orderableCourses(List<UUID> ids) {
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<OrderCourse> found = jdbc.query("select id, code from courses where id in (" + marks + ") and owner_org_id is null and status = 'PUBLISHED'",
                (rs, i) -> new OrderCourse(rs.getObject("id", UUID.class), rs.getString("code").strip()), ids.toArray());
        List<OrderCourse> ordered = new ArrayList<>();
        for (UUID id : ids) {
            found.stream().filter(c -> c.id().equals(id)).findFirst().ifPresent(ordered::add);
        }
        return ordered;
    }

    /** Inserts the order unless the code is taken; false = taken (try another). The id is generated here, the role may not read before it owns the row. */
    boolean insertOrder(UUID id, String code, UUID userId, UUID planId, int planMonths, long amountVnd, Instant createdAt) {
        return jdbc.update("insert into orders(id, code, user_id, plan_id, plan_months, amount_vnd, created_at) values (?, ?, ?, ?, ?, ?, ?) on conflict do nothing",
                id, code, userId, planId, planMonths, amountVnd, Timestamp.from(createdAt)) == 1;
    }

    void insertItem(UUID orderId, UUID userId, OrderCourse course) {
        jdbc.update("insert into order_items(order_id, user_id, course_id, course_code) values (?, ?, ?, ?)", orderId, userId, course.id(), course.code());
    }

    List<OrderRow> mine(UUID userId) {
        return jdbc.query("select id, code, status, amount_vnd, plan_id, plan_months, created_at, reject_reason from orders where user_id = ? order by created_at desc, id",
                (rs, i) -> row(rs), userId);
    }

    Optional<OrderRow> mine(UUID userId, String code) {
        return jdbc.query("select id, code, status, amount_vnd, plan_id, plan_months, created_at, reject_reason from orders where user_id = ? and code = ?",
                (rs, i) -> row(rs), userId, code).stream().findFirst();
    }

    List<OrderCourse> itemsOf(UUID orderId, UUID userId) {
        return jdbc.query("select course_id, course_code from order_items where order_id = ? and user_id = ? order by course_code",
                (rs, i) -> new OrderCourse(rs.getObject("course_id", UUID.class), rs.getString("course_code").strip()), orderId, userId);
    }

    private static OrderRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderRow(rs.getObject("id", UUID.class), rs.getString("code").strip(), rs.getString("status"), rs.getLong("amount_vnd"),
                rs.getObject("plan_id", UUID.class), rs.getInt("plan_months"), rs.getTimestamp("created_at").toInstant(), rs.getString("reject_reason"));
    }
}
