package com.english9x.billing.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.billing.BillingIT;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** F42: organisation licences, created by the SYS_ADMIN: a contract is active at once, a QR licence after its order is confirmed. */
class LicenseIT extends BillingIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Access tokens are signed with real time and checked against the application clock: another test class may have moved it. */
    @BeforeEach
    void realTime() {
        clock.set(Instant.now());
    }

    private Map<String, Object> body(UUID org, int seats, int months, long price, String activation, String contractNo, UUID... courses) {
        Map<String, Object> b = new java.util.HashMap<>();
        b.put("orgId", org);
        b.put("seats", seats);
        b.put("months", months);
        b.put("courseIds", List.of(courses));
        b.put("priceVnd", price);
        b.put("activation", activation);
        b.put("contractNo", contractNo);
        return b;
    }

    @Test
    void contractActivationImmediate() {
        UUID admin = sysAdmin();
        UUID org = org("ENTERPRISE");
        UUID orgAdmin = orgAdmin(org);
        UUID c1 = course("PUBLISHED");
        UUID c2 = course("PUBLISHED");

        assertEquals(403, post("/api/admin/org-licenses", body(org, 10, 6, 50_000_000, "CONTRACT", "HD-2026-01", c1), orgAdmin, org).status(), "an ORG_ADMIN cannot sell a licence to itself");
        assertEquals(403, post("/api/admin/org-licenses", body(org, 10, 6, 50_000_000, "CONTRACT", "HD-2026-01", c1), user("l"), null).status());
        assertEquals(400, post("/api/admin/org-licenses", body(org, 10, 6, 50_000_000, "CONTRACT", null, c1), admin, null).status(), "a contract needs its number");
        assertEquals(400, post("/api/admin/org-licenses", body(org, 10, 6, 50_000_000, "QR", "HD-1", c1), admin, null).status(), "a QR licence has no contract number");
        assertEquals(400, post("/api/admin/org-licenses", body(org, 0, 6, 50_000_000, "CONTRACT", "HD-1", c1), admin, null).status());
        assertEquals(400, post("/api/admin/org-licenses", body(org, 10, 6, 0, "CONTRACT", "HD-1", c1), admin, null).status());
        assertEquals(400, post("/api/admin/org-licenses", body(org, 10, 6, 1, "CONTRACT", "HD-1", course("DRAFT")), admin, null).status(), "only published English9x courses");
        assertEquals(404, post("/api/admin/org-licenses", body(UUID.randomUUID(), 10, 6, 1, "CONTRACT", "HD-1", c1), admin, null).status());
        assertEquals(0, su.queryForObject("select count(*) from org_licenses where org_id = ?", Integer.class, org));

        Instant before = Instant.now();
        Res r = post("/api/admin/org-licenses", body(org, 10, 6, 50_000_000, "CONTRACT", "HD-2026-01", c1, c2), admin, null);
        assertEquals(201, r.status(), r.raw());
        assertEquals("ACTIVE", r.body().path("license").path("status").asText());
        assertEquals(10, r.body().path("license").path("seats").asInt());
        assertEquals(0, r.body().path("license").path("seatsUsed").asInt());
        assertEquals(50_000_000L, r.body().path("license").path("priceVnd").asLong());
        assertTrue(r.body().path("license").path("orderCode").isNull());
        assertEquals(2, r.body().path("license").path("courses").size());
        UUID id = UUID.fromString(r.body().path("license").path("id").asText());
        Map<String, Object> row = su.queryForMap("select status, valid_from, valid_until, price_vnd, months, contract_no from org_licenses where id = ?", id);
        Instant from = ((Timestamp) row.get("valid_from")).toInstant();
        assertFalse(from.isBefore(before.minusSeconds(1)));
        assertEquals(from.atZone(VN).plusMonths(6).toInstant().toEpochMilli(), ((Timestamp) row.get("valid_until")).toInstant().toEpochMilli(), "six months from now on the Vietnamese calendar");
        assertEquals(0, su.queryForObject("select count(*) from orders where license_id = ?", Integer.class, id), "a contract has no order");

        // audited in the same transaction: no email
        List<Map<String, Object>> log = su.queryForList("select action, target_type, detail::text d from audit_log where target_id = ?", id.toString());
        assertEquals(1, log.size());
        assertEquals("license.create", log.get(0).get("action"));
        assertEquals("license", log.get(0).get("target_type"));
        assertTrue(log.get(0).get("d").toString().contains("HD-2026-01"));
        assertFalse(log.get(0).get("d").toString().contains("@"));

        // the organisation's administrator reads it, another organisation's does not
        Res mine = get("/api/orgs/" + org + "/licenses", orgAdmin, org);
        assertEquals(200, mine.status(), mine.raw());
        assertEquals(1, mine.body().size());
        assertEquals(id.toString(), mine.body().get(0).path("id").asText());
        UUID other = org("CENTER");
        assertEquals(404, get("/api/orgs/" + org + "/licenses", orgAdmin(other), other).status());
        assertEquals(403, get("/api/orgs/" + org + "/licenses", user("m"), org).status());
        assertEquals(1, get("/api/admin/org-licenses", admin, null).body().findValuesAsText("id").stream().filter(i -> i.equals(id.toString())).count());
    }

    @Test
    void qrActivationAfterConfirm() {
        UUID admin = sysAdmin();
        UUID org = org("CENTER");
        UUID orgAdmin = orgAdmin(org);
        UUID member = user("member");
        member(org, member);
        UUID c1 = course("PUBLISHED");

        // an organisation without an administrator has nobody to pay
        assertEquals(422, post("/api/admin/org-licenses", body(org("CENTER"), 3, 12, 9_000_000, "QR", null, c1), admin, null).status());

        Res r = post("/api/admin/org-licenses", body(org, 3, 12, 9_000_000, "QR", null, c1), admin, null);
        assertEquals(201, r.status(), r.raw());
        assertEquals("PENDING_PAYMENT", r.body().path("license").path("status").asText());
        String code = r.body().path("license").path("orderCode").asText();
        assertEquals(6, code.length());
        assertTrue(r.body().path("transferNote").asText().startsWith("E9X " + code));
        UUID id = UUID.fromString(r.body().path("license").path("id").asText());
        assertNull(su.queryForObject("select valid_until from org_licenses where id = ?", Timestamp.class, id));

        // the order is the ORG_ADMIN's, at the licence's price, with no plan
        Map<String, Object> order = su.queryForMap("select user_id, amount_vnd, plan_months, plan_id, license_id, status from orders where code = ?", code);
        assertEquals(orgAdmin, order.get("user_id"));
        assertEquals(9_000_000L, order.get("amount_vnd"));
        assertEquals(12, order.get("plan_months"));
        assertNull(order.get("plan_id"));
        assertEquals(id, order.get("license_id"));
        Res seen = get("/api/orders/" + code, orgAdmin, null);
        assertEquals(200, seen.status(), seen.raw());
        assertEquals("PENDING_PAYMENT", seen.body().path("status").asText());
        assertEquals(id.toString(), seen.body().path("licenseId").asText());
        assertTrue(seen.body().path("planId").isNull());
        assertEquals(1, seen.body().path("courses").size());

        // not active yet: no seats, and a second confirm of a different kind of order does not exist
        Res early = post("/api/orgs/" + org + "/licenses/" + id + "/seats", Map.of("userId", member, "courseIds", List.of(c1)), orgAdmin, org);
        assertEquals(409, early.status(), early.raw());
        assertEquals("urn:e9x:problem:license-not-active", early.problemType());
        assertEquals(403, post("/api/admin/orders/" + code + "/confirm", Map.of(), orgAdmin, null).status(), "the payer cannot confirm their own payment");

        Instant at = Instant.now();
        Res ok = post("/api/admin/orders/" + code + "/confirm", Map.of("bankRef", "FT26111200001"), admin, null);
        assertEquals(200, ok.status(), ok.raw());
        Map<String, Object> lic = su.queryForMap("select status, valid_from, valid_until from org_licenses where id = ?", id);
        assertEquals("ACTIVE", lic.get("status"));
        Instant from = ((Timestamp) lic.get("valid_from")).toInstant();
        assertFalse(from.isBefore(at.minusSeconds(1)), "the months start when the payment is confirmed");
        assertEquals(from.atZone(VN).plusMonths(12).toInstant().toEpochMilli(), ((Timestamp) lic.get("valid_until")).toInstant().toEpochMilli());
        assertEquals(0, su.queryForObject("select count(*) from entitlements where source_order_id = (select id from orders where code = ?)", Integer.class, code), "the payer gets no personal entitlement");
        assertEquals(1, su.queryForObject("select count(*) from audit_log where action = 'license.change' and target_id = ?", Integer.class, id.toString()));
        assertEquals(1, su.queryForObject("select count(*) from audit_log where action = 'license.create' and target_id = ?", Integer.class, id.toString()));
        assertEquals(409, post("/api/admin/orders/" + code + "/confirm", Map.of(), admin, null).status());

        Res seat = post("/api/orgs/" + org + "/licenses/" + id + "/seats", Map.of("userId", member, "courseIds", List.of(c1)), orgAdmin, org);
        assertEquals(201, seat.status(), seat.raw());
        assertEquals(1, get("/api/orgs/" + org + "/licenses", orgAdmin, org).body().get(0).path("seatsUsed").asInt());
    }

    @Test
    void rejectedQrOrderCancelsTheLicense() {
        UUID admin = sysAdmin();
        UUID org = org("CENTER");
        orgAdmin(org);
        Res r = post("/api/admin/org-licenses", body(org, 3, 12, 9_000_000, "QR", null, course("PUBLISHED")), admin, null);
        assertEquals(201, r.status(), r.raw());
        String code = r.body().path("license").path("orderCode").asText();
        UUID id = UUID.fromString(r.body().path("license").path("id").asText());
        assertEquals(200, post("/api/admin/orders/" + code + "/reject", Map.of("reason", "wrong amount"), admin, null).status());
        assertEquals("CANCELLED", su.queryForObject("select status from org_licenses where id = ?", String.class, id));
        assertNotNull(su.queryForObject("select count(*) from audit_log where action = 'license.change' and target_id = ?", Integer.class, id.toString()));
        // and an expired one: the worker's function cancels the licence as well
        Res r2 = post("/api/admin/org-licenses", body(org, 3, 12, 9_000_000, "QR", null, course("PUBLISHED")), admin, null);
        UUID id2 = UUID.fromString(r2.body().path("license").path("id").asText());
        su.update("update orders set created_at = now() - interval '30 days' where license_id = ?", id2);
        runJob("expire_orders");
        assertEquals("CANCELLED", su.queryForObject("select status from org_licenses where id = ?", String.class, id2));
    }
}
