package com.english9x.billing.payment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The administrator's side of orders. Runs under the caller's Row-Level Security: only a real SYS_ADMIN sees other people's orders or may decide one. */
@Repository
class OrderAdminRepository {

    record AdminOrder(UUID id, String code, UUID userId, UUID planId, int planMonths, long amountVnd, String status, Instant createdAt, Instant confirmedAt,
                      String bankRef, String rejectReason) {}

    private static final String COLUMNS = "id, code, user_id, plan_id, plan_months, amount_vnd, status, created_at, confirmed_at, bank_ref, reject_reason";

    private final JdbcTemplate jdbc;

    OrderAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<AdminOrder> list(String status, int limit) {
        return jdbc.query("select " + COLUMNS + " from orders where (?::text is null or status = ?) order by created_at desc, id limit ?",
                (rs, i) -> row(rs), status, status, limit);
    }

    Optional<AdminOrder> find(String code) {
        return jdbc.query("select " + COLUMNS + " from orders where code = ?", (rs, i) -> row(rs), code).stream().findFirst();
    }

    /** Course ids and codes of one order, in code order. */
    Map<UUID, String> items(UUID orderId) {
        Map<UUID, String> out = new LinkedHashMap<>();
        jdbc.query("select course_id, course_code from order_items where order_id = ? order by course_code",
                rs -> {
                    out.put(rs.getObject("course_id", UUID.class), rs.getString("course_code").strip());
                }, orderId);
        return out;
    }

    /** Course codes per order for several orders at once. */
    Map<UUID, List<String>> courseCodes(Collection<UUID> orderIds) {
        Map<UUID, List<String>> out = new LinkedHashMap<>();
        if (orderIds.isEmpty()) {
            return out;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement("select order_id, course_code from order_items where order_id = any(?) order by course_code");
            ps.setArray(1, con.createArrayOf("uuid", orderIds.toArray()));
            return ps;
        }, rs -> {
            out.computeIfAbsent(rs.getObject("order_id", UUID.class), k -> new ArrayList<>()).add(rs.getString("course_code").strip());
        });
        return out;
    }

    /** PENDING_PAYMENT to CONFIRMED. False when the order was not pending any more (the guard is in the statement and in the UPDATE policy). */
    boolean confirm(String code, Instant at, String bankRef) {
        return jdbc.update("update orders set status = 'CONFIRMED', confirmed_at = ?, bank_ref = ? where code = ? and status = 'PENDING_PAYMENT'",
                Timestamp.from(at), bankRef, code) == 1;
    }

    boolean reject(String code, String reason) {
        return jdbc.update("update orders set status = 'REJECTED', reject_reason = ? where code = ? and status = 'PENDING_PAYMENT'", reason, code) == 1;
    }

    void insertEntitlement(UUID userId, UUID courseId, Instant from, Instant until, UUID orderId) {
        jdbc.update("insert into entitlements(user_id, course_id, valid_from, valid_until, source_order_id) values (?, ?, ?, ?, ?)",
                userId, courseId, Timestamp.from(from), Timestamp.from(until), orderId);
    }

    private static AdminOrder row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp confirmed = rs.getTimestamp("confirmed_at");
        return new AdminOrder(rs.getObject("id", UUID.class), rs.getString("code").strip(), rs.getObject("user_id", UUID.class), rs.getObject("plan_id", UUID.class),
                rs.getInt("plan_months"), rs.getLong("amount_vnd"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                confirmed == null ? null : confirmed.toInstant(), rs.getString("bank_ref"), rs.getString("reject_reason"));
    }
}
