package com.english9x.billing.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.billing.BillingIT;
import com.english9x.domain.billing.Codes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class OrderServiceIT extends BillingIT {

    private Res order(UUID user, UUID plan, UUID... courses) {
        return post("/api/orders", Map.of("planId", plan, "courseIds", List.of(courses)), user, null);
    }

    @Test
    void createsAPendingOrderWithTheLockedPriceAndATransferNote() {
        UUID u = user("buyer");
        UUID plan = plan(499_000, 3, 2, true);
        UUID c = course("PUBLISHED");
        Res r = order(u, plan, c);
        assertEquals(201, r.status(), r.raw());
        String code = r.body().path("orderCode").asText();
        assertTrue(Codes.isValidOrderCode(code));
        assertEquals(499_000, r.body().path("amountVnd").asLong());
        assertTrue(r.body().path("transferNote").asText().startsWith("E9X " + code));
        assertEquals("PENDING_PAYMENT", su.queryForObject("select status from orders where code = ?", String.class, code));
        assertEquals(1, su.queryForObject("select count(*) from audit_log where action = 'order.create' and actor_id = ?", Integer.class, u));
        Res mine = get("/api/orders/" + code, u, null);
        assertEquals(200, mine.status());
        assertEquals(1, mine.body().path("courses").size());
        assertEquals(404, get("/api/orders/" + code, user("other"), null).status(), "nobody reads another person's order");
        assertEquals(1, get("/api/orders", u, null).body().size());
    }

    @Test
    void rejectsMoreCoursesThanPlan() {
        UUID u = user("greedy");
        UUID plan = plan(100_000, 1, 1, true);
        Res r = order(u, plan, course("PUBLISHED"), course("PUBLISHED"));
        assertEquals(400, r.status(), r.raw());
        assertEquals("urn:e9x:problem:validation-failed", r.problemType());
        assertEquals(0, su.queryForObject("select count(*) from orders where user_id = ?", Integer.class, u));
    }

    @Test
    void onlyPublishedEnglish9xCoursesAndOnlyPlansOnSale() {
        UUID u = user("picky");
        UUID plan = plan(100_000, 1, 3, true);
        assertEquals(400, order(u, plan, course("DRAFT")).status());
        assertEquals(400, order(u, plan, UUID.randomUUID()).status());
        UUID org = org("CENTER");
        UUID own = UUID.randomUUID();
        su.update("insert into courses(id,owner_org_id,exam_code,code,title,status) values (?,?,'IELTS','ZZ"
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase().replace("-", "A") + "','Org course','PUBLISHED')", own, org);
        assertEquals(400, order(u, plan, own).status(), "an organisation's course is not sold to individuals");
        assertEquals(409, order(u, plan(100_000, 1, 1, false), course("PUBLISHED")).status());
        assertEquals(409, order(u, UUID.randomUUID(), course("PUBLISHED")).status());
        assertEquals(400, post("/api/orders", Map.of("planId", plan, "courseIds", List.of()), u, null).status());
    }

    @Test
    void unverifiedEmailCannotOrder() {
        UUID u = user(email("unverified"), false);
        Res r = order(u, plan(100_000, 1, 1, true), course("PUBLISHED"));
        assertEquals(403, r.status(), r.raw());
        assertEquals("urn:e9x:problem:email-not-verified", r.problemType());
        assertEquals(0, su.queryForObject("select count(*) from orders where user_id = ?", Integer.class, u));
    }

    @Test
    void priceLockedAtOrderTime() {
        UUID u = user("lock");
        UUID admin = sysAdmin();
        UUID plan = plan(300_000, 2, 1, true);
        String code = order(u, plan, course("PUBLISHED")).body().path("orderCode").asText();
        Res put = call(HttpMethod.PUT, "/api/admin/plans/" + plan, Map.of("name", "Dearer", "priceVnd", 900_000, "months", 12, "maxCourses", 1, "active", true), admin, null);
        assertEquals(200, put.status(), put.raw());
        assertEquals(300_000, get("/api/orders/" + code, u, null).body().path("amountVnd").asLong());
        assertEquals(2, get("/api/orders/" + code, u, null).body().path("planMonths").asInt());
        assertEquals(300_000, su.queryForObject("select amount_vnd from orders where code = ?", Long.class, code));
        assertEquals(1, su.queryForObject("select count(*) from audit_log where action = 'plan.update' and target_id = ?", Integer.class, plan.toString()));
    }

    @Test
    void orderCodesUnique() throws Exception {
        UUID plan = plan(100_000, 1, 1, true);
        UUID c = course("PUBLISHED");
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            users.add(user("par"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<String>> fs = new ArrayList<>();
        for (UUID u : users) {
            Callable<String> call = () -> order(u, plan, c).body().path("orderCode").asText();
            fs.add(pool.submit(call));
        }
        Set<String> codes = new HashSet<>();
        for (Future<String> f : fs) {
            String code = f.get();
            assertTrue(Codes.isValidOrderCode(code), code);
            codes.add(code);
        }
        pool.shutdown();
        assertEquals(users.size(), codes.size(), "every order has its own code");
    }

    @Test
    void planAdminIsSysAdminOnlyAndLearnersSeeOnlyPlansOnSale() {
        UUID learner = user("l");
        UUID onSale = plan(111_000, 1, 1, true);
        UUID off = plan(222_000, 1, 1, false);
        Map<String, Object> body = Map.of("name", "X", "priceVnd", 1000, "months", 1, "maxCourses", 1, "active", true);
        assertEquals(403, post("/api/admin/plans", body, learner, null).status());
        assertEquals(403, call(HttpMethod.PUT, "/api/admin/plans/" + onSale, body, learner, null).status());
        assertEquals(403, get("/api/admin/plans", learner, null).status());
        List<String> ids = new ArrayList<>();
        get("/api/plans", learner, null).body().forEach(n -> ids.add(n.path("id").asText()));
        assertTrue(ids.contains(onSale.toString()) && !ids.contains(off.toString()));
        UUID admin = sysAdmin();
        assertEquals(201, post("/api/admin/plans", body, admin, null).status());
        assertEquals(400, post("/api/admin/plans", Map.of("name", "", "priceVnd", 0, "months", 0, "maxCourses", 0, "active", true), admin, null).status());
        assertEquals(404, call(HttpMethod.PUT, "/api/admin/plans/" + UUID.randomUUID(), body, admin, null).status());
        assertTrue(get("/api/admin/plans", admin, null).body().toString().contains(off.toString()));
        assertNotEquals(0, su.queryForObject("select count(*) from audit_log where action = 'plan.create'", Integer.class));
    }
}
