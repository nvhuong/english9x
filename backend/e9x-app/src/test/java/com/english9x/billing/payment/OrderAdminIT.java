package com.english9x.billing.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class OrderAdminIT extends PaymentIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private Res confirm(String code, Object bankRef, UUID admin) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("bankRef", bankRef);
        return post("/api/admin/orders/" + code + "/confirm", body, admin, null);
    }

    private Res reject(String code, String reason, UUID admin) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("reason", reason);
        return post("/api/admin/orders/" + code + "/reject", body, admin, null);
    }

    private int count(String sql, Object... args) {
        return su.queryForObject(sql, Integer.class, args);
    }

    @Test
    void confirmCreatesEntitlementsFromConfirmationTime() {
        account(BIN, ACCOUNT_NO);
        UUID admin = sysAdmin();
        UUID u = user("buyer");
        UUID plan = plan(499_000, 3, 2, true);
        UUID c1 = course("PUBLISHED");
        UUID c2 = course("PUBLISHED");
        String code = placeOrder(u, plan, c1, c2).body().path("orderCode").asText();
        // the plan is repriced and lengthened after the order: the order keeps what it locked
        assertEquals(200, call(HttpMethod.PUT, "/api/admin/plans/" + plan, Map.of("name", "X", "priceVnd", 1_000_000, "months", 12, "maxCourses", 2, "active", true), admin, null).status());
        // the order was placed two days ago (the clock cannot move: the access tokens are checked against it)
        su.update("update orders set created_at = now() - interval '2 days' where code = ?", code);
        Instant confirmedAt = clock.instant();

        assertEquals(403, confirm(code, "FT1", u).status(), "a learner cannot confirm");
        assertEquals(0, count("select count(*) from entitlements where source_order_id = (select id from orders where code = ?)", code));

        Res ok = confirm(code, "FT26110912345", admin);
        assertEquals(200, ok.status(), ok.raw());
        assertEquals("CONFIRMED", ok.body().path("status").asText());
        Map<String, Object> o = su.queryForMap("select id, status, confirmed_at, bank_ref, created_at from orders where code = ?", code);
        assertEquals("CONFIRMED", o.get("status"));
        assertEquals("FT26110912345", o.get("bank_ref"));
        Instant storedConfirmedAt = ((Timestamp) o.get("confirmed_at")).toInstant();
        assertEquals(confirmedAt.toEpochMilli(), storedConfirmedAt.toEpochMilli(), "the clock at confirmation, not the order time");
        assertNotEquals(((Timestamp) o.get("created_at")).toInstant().toEpochMilli(), storedConfirmedAt.toEpochMilli());

        List<Map<String, Object>> ents = su.queryForList("select user_id, course_id, valid_from, valid_until, source_order_id, source_seat_id from entitlements where source_order_id = ?", o.get("id"));
        assertEquals(2, ents.size());
        for (Map<String, Object> e : ents) {
            assertEquals(u, e.get("user_id"));
            assertEquals(storedConfirmedAt.toEpochMilli(), ((Timestamp) e.get("valid_from")).toInstant().toEpochMilli(), "validity starts when the payment is confirmed");
            assertEquals(storedConfirmedAt.atZone(VN).plusMonths(3).toInstant().toEpochMilli(), ((Timestamp) e.get("valid_until")).toInstant().toEpochMilli(),
                    "three months: the length locked on the order, not the plan's 12 now");
        }
        assertEquals(Set2.of(c1, c2), Set2.of(ents.get(0).get("course_id"), ents.get(1).get("course_id")));
        // the learner now holds access and is told
        Res mine = get("/api/me/entitlements", u, null);
        assertEquals(200, mine.status());
        assertEquals(2, mine.body().size());
        assertTrue(mine.body().get(0).path("active").asBoolean());
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals(1,
                count("select count(*) from notifications where user_id = ? and event_type = 'ORDER_CONFIRMED'", u)));
    }

    /** Order-insensitive comparison of two ids. */
    private static final class Set2 {
        static java.util.Set<Object> of(Object a, Object b) {
            return new java.util.HashSet<>(List.of(a, b));
        }
    }

    @Test
    void rejectRequiresReason() {
        account(BIN, ACCOUNT_NO);
        UUID admin = sysAdmin();
        UUID u = user("rej");
        String code = placeOrder(u, plan(100_000, 1, 1, true), course("PUBLISHED")).body().path("orderCode").asText();
        for (String bad : new String[] {null, "", "   ", "x".repeat(501)}) {
            Res r = reject(code, bad, admin);
            assertEquals(422, r.status(), "reason " + (bad == null ? "null" : bad.length() + " chars") + ": " + r.raw());
        }
        assertEquals("PENDING_PAYMENT", su.queryForObject("select status from orders where code = ?", String.class, code));
        assertEquals(403, reject(code, "no", u).status());
        assertEquals(404, reject("ZZZZZZ", "no", admin).status());

        Res ok = reject(code, "Amount received does not match <b>the order</b>", admin);
        assertEquals(200, ok.status(), ok.raw());
        assertEquals("REJECTED", ok.body().path("status").asText());
        assertEquals("REJECTED", su.queryForObject("select status from orders where code = ?", String.class, code));
        assertEquals(0, count("select count(*) from entitlements where source_order_id = (select id from orders where code = ?)", code));
        assertEquals("Amount received does not match <b>the order</b>", get("/api/orders/" + code, u, null).body().path("rejectReason").asText());
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals(1,
                count("select count(*) from notifications where user_id = ? and event_type = 'ORDER_REJECTED'", u)));
        assertEquals(409, confirm(code, null, admin).status(), "a rejected order cannot be confirmed afterwards");
    }

    @Test
    void confirmTwiceConflict() {
        account(BIN, ACCOUNT_NO);
        UUID admin = sysAdmin();
        UUID u = user("twice");
        String code = placeOrder(u, plan(100_000, 1, 1, true), course("PUBLISHED")).body().path("orderCode").asText();
        assertEquals(200, confirm(code, null, admin).status());
        Res again = confirm(code, "OTHER", admin);
        assertEquals(409, again.status(), again.raw());
        assertEquals("urn:e9x:problem:order-not-pending", again.problemType());
        assertEquals(409, reject(code, "too late", admin).status());
        assertEquals(1, count("select count(*) from entitlements where source_order_id = (select id from orders where code = ?)", code), "no second set of entitlements");
        assertEquals(1, count("select count(*) from audit_log where action = 'order.confirm' and target_id = (select id::text from orders where code = ?)", code));
        assertTrue(su.queryForObject("select bank_ref from orders where code = ?", String.class, code) == null, "the refused second confirm changed nothing");

        // an expired order is not pending either, and the expiry job never touches a confirmed one
        String old = placeOrder(u, plan(100_000, 1, 1, true), course("PUBLISHED")).body().path("orderCode").asText();
        su.update("update orders set status = 'EXPIRED', expired_at = now() where code = ?", old);
        assertEquals(409, confirm(old, null, admin).status());
        su.update("update orders set created_at = now() - interval '30 days' where code = ?", code);
        runJob("expire_orders");
        assertEquals("CONFIRMED", su.queryForObject("select status from orders where code = ?", String.class, code));
    }

    @Test
    void concurrentConfirmsMakeOneSetOfEntitlements() throws Exception {
        account(BIN, ACCOUNT_NO);
        UUID admin = sysAdmin();
        UUID u = user("race");
        String code = placeOrder(u, plan(100_000, 1, 1, true), course("PUBLISHED")).body().path("orderCode").asText();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            fs.add(pool.submit(() -> confirm(code, null, admin).status()));
        }
        int ok = 0;
        int conflict = 0;
        for (var f : fs) {
            int s = f.get();
            if (s == 200) ok++;
            else if (s == 409) conflict++;
        }
        pool.shutdown();
        assertEquals(1, ok);
        assertEquals(3, conflict);
        assertEquals(1, count("select count(*) from entitlements where source_order_id = (select id from orders where code = ?)", code));
        assertEquals(1, count("select count(*) from audit_log where action = 'order.confirm' and target_id = (select id::text from orders where code = ?)", code));
    }

    @Test
    void auditRecorded() {
        account(BIN, ACCOUNT_NO);
        UUID admin = sysAdmin();
        UUID u = user("aud");
        UUID c = course("PUBLISHED");
        String courseCode = su.queryForObject("select code from courses where id = ?", String.class, c).strip();
        String code = placeOrder(u, plan(250_000, 2, 1, true), c).body().path("orderCode").asText();
        UUID orderId = su.queryForObject("select id from orders where code = ?", UUID.class, code);
        assertEquals(200, confirm(code, "FT777", admin).status());
        Map<String, Object> row = su.queryForMap("select actor_kind, actor_id, target_type, target_id, detail::text as detail from audit_log where action = 'order.confirm' and target_id = ?", orderId.toString());
        assertEquals("USER", row.get("actor_kind"));
        assertEquals(admin, row.get("actor_id"));
        assertEquals("order", row.get("target_type"));
        JsonNode d = parse((String) row.get("detail"));
        assertEquals(code, d.path("orderCode").asText());
        assertEquals(250_000, d.path("amountVnd").asLong());
        assertEquals(2, d.path("planMonths").asInt());
        assertEquals(courseCode, d.path("courseCodes").get(0).asText());
        assertEquals("FT777", d.path("bankRef").asText());
        assertFalse((row.get("detail")).toString().contains(ACCOUNT_NO), "never an account number");
        assertEquals(java.util.Set.of("orderCode", "amountVnd", "planMonths", "courseCodes", "bankRef"), keys(d), "minimal shape");

        String reason = "Secret free text the payer wrote about their bank";
        String code2 = placeOrder(u, plan(250_000, 2, 1, true), course("PUBLISHED")).body().path("orderCode").asText();
        UUID orderId2 = su.queryForObject("select id from orders where code = ?", UUID.class, code2);
        assertEquals(200, reject(code2, reason, admin).status());
        String detail2 = su.queryForObject("select detail::text from audit_log where action = 'order.reject' and target_id = ?", String.class, orderId2.toString());
        JsonNode d2 = parse(detail2);
        assertEquals(reason.length(), d2.path("rejectReasonLength").asInt());
        assertFalse(detail2.contains("Secret"), "the free text is never recorded");
        assertEquals(java.util.Set.of("orderCode", "amountVnd", "planMonths", "courseCodes", "rejectReasonLength"), keys(d2));

        // a refused decision leaves no record
        assertEquals(409, confirm(code2, null, admin).status());
        assertEquals(0, count("select count(*) from audit_log where action = 'order.confirm' and target_id = ?", orderId2.toString()));
    }

    @Test
    void paymentAccountAdminIsOneActiveAccountAndAudited() {
        UUID admin = sysAdmin();
        UUID learner = user("pa");
        Map<String, Object> body = Map.of("bankBin", "970415", "accountNo", "99887766", "accountName", "ENGLISH9X JSC");
        assertEquals(403, call(HttpMethod.PUT, "/api/admin/payment-account", body, learner, null).status());
        assertEquals(403, get("/api/admin/payment-account", learner, null).status());
        assertEquals(400, call(HttpMethod.PUT, "/api/admin/payment-account", Map.of("bankBin", "12", "accountNo", "99887766", "accountName", "N"), admin, null).status());
        assertEquals(400, call(HttpMethod.PUT, "/api/admin/payment-account", Map.of("bankBin", "970415", "accountNo", "99 887", "accountName", "N"), admin, null).status());
        assertEquals(200, call(HttpMethod.PUT, "/api/admin/payment-account", body, admin, null).status());
        Res second = call(HttpMethod.PUT, "/api/admin/payment-account", Map.of("bankBin", "970436", "accountNo", "55443322", "accountName", "ENGLISH9X JSC"), admin, null);
        assertEquals(200, second.status(), second.raw());
        assertEquals(1, count("select count(*) from payment_accounts where active"));
        assertEquals("55443322", get("/api/admin/payment-account", admin, null).body().path("accountNo").asText());
        String detail = su.queryForObject("select detail::text from audit_log where action = 'payment-account.update' order by seq desc limit 1", String.class);
        assertFalse(detail.contains("55443322") || detail.contains("99887766"), "the audit record never holds an account number");
        assertEquals(2, count("select count(*) from audit_log where action = 'payment-account.update' and actor_id = ?", admin));
    }

    @Test
    void adminQueueIsSysAdminOnlyAndFilters() {
        account(BIN, ACCOUNT_NO);
        UUID admin = sysAdmin();
        UUID u = user("queue");
        String a = placeOrder(u, plan(100_000, 1, 1, true), course("PUBLISHED")).body().path("orderCode").asText();
        String b = placeOrder(u, plan(100_000, 1, 1, true), course("PUBLISHED")).body().path("orderCode").asText();
        assertEquals(200, confirm(b, null, admin).status());
        assertEquals(403, get("/api/admin/orders?status=PENDING_PAYMENT", u, null).status());
        Res pending = get("/api/admin/orders?status=PENDING_PAYMENT", admin, null);
        assertEquals(200, pending.status(), pending.raw());
        List<String> codes = new ArrayList<>();
        pending.body().forEach(n -> codes.add(n.path("code").asText()));
        assertTrue(codes.contains(a) && !codes.contains(b));
        Res byCode = get("/api/admin/orders?q=" + a.toLowerCase(), admin, null);
        assertEquals(1, byCode.body().size());
        assertEquals(a, byCode.body().get(0).path("code").asText());
        assertTrue(byCode.body().get(0).path("userEmail").asText().startsWith("queue-"));
        assertEquals(400, get("/api/admin/orders?status=BOGUS", admin, null).status());
        assertTrue(get("/api/admin/orders?status=CONFIRMED&q=" + b, admin, null).body().size() == 1);
    }

    @Test
    void newOrderNotifiesEverySysAdminOnceAndNobodyElse() {
        account(BIN, ACCOUNT_NO);
        UUID admin1 = sysAdmin();
        UUID admin2 = sysAdmin();
        UUID u = user("pend");
        UUID bystander = user("bystander");
        String code = placeOrder(u, plan(100_000, 1, 1, true), course("PUBLISHED")).body().path("orderCode").asText();
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertEquals(1, count("select count(*) from notifications where user_id = ? and event_type = 'ORDER_PENDING' and data->>'orderCode' = ?", admin1, code));
            assertEquals(1, count("select count(*) from notifications where user_id = ? and event_type = 'ORDER_PENDING' and data->>'orderCode' = ?", admin2, code));
        });
        assertEquals(0, count("select count(*) from notifications where user_id in (?, ?) and event_type = 'ORDER_PENDING'", u, bystander));
    }

    @Test
    void longRejectReasonIsTruncatedForTheNotification() {
        account(BIN, ACCOUNT_NO);
        UUID admin = sysAdmin();
        UUID u = user("longreason");
        String code = placeOrder(u, plan(100_000, 1, 1, true), course("PUBLISHED")).body().path("orderCode").asText();
        assertEquals(200, reject(code, "r".repeat(500), admin).status());
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals(1,
                count("select count(*) from notifications where user_id = ? and event_type = 'ORDER_REJECTED' and length(data->>'reason') <= 500", u)));
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.util.Set<String> keys(JsonNode n) {
        java.util.Set<String> out = new java.util.HashSet<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }
}
