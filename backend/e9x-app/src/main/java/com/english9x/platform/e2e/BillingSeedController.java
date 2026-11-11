package com.english9x.platform.e2e;

import com.english9x.platform.jobs.api.JobQueue;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * E2E seeds of billing (P5.1), under the profile {@code e2e} and the seed secret like the others: a published course, an entitlement with any
 * dates, an order of any age and the real billing jobs run on demand (a tick queued under a unique key, then handled by the real worker). The gate probe lives in the
 * billing module (BillingGateProbe): this module may not depend on billing.
 */
@RestController
@Profile("e2e")
@RequestMapping("/api/test/seed/billing")
class BillingSeedController {

    private final SeedDatabase db;
    private final JobQueue jobs;
    private final TransactionTemplate tx;

    BillingSeedController(SeedDatabase db, JobQueue jobs, PlatformTransactionManager tm) {
        this.db = db;
        this.jobs = jobs;
        this.tx = new TransactionTemplate(tm);
    }

    @PostMapping("/course")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> course(@RequestBody(required = false) Map<String, String> body) {
        String exam = body == null || body.get("examCode") == null ? "IELTS" : body.get("examCode");
        db.requireCatalogExam(exam);
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        UUID id = db.jdbc().queryForObject("insert into courses(owner_org_id, exam_code, code, title, status) values (null, ?, ?, 'E2E course', 'PUBLISHED') returning id", UUID.class, exam, code);
        // the public catalogue lists a course by its PUBLISHED version (as the table owner may insert, like a finished review would leave it), so the course shows on the checkout screen
        db.jdbc().update("insert into course_versions(course_id, owner_org_id, exam_code, version_no, status, title, description, author_display, unlock_rule, created_by) "
                + "values (?, null, ?, 1, 'PUBLISHED', 'E2E course', 'A course of the E2E seed', 'English9x', 'FREE', (select id from users order by created_at limit 1))", id, exam);
        return Map.of("courseId", id.toString(), "code", code);
    }

    record EntitlementRequest(UUID userId, UUID courseId, Integer startsInDays, Integer endsInDays, Integer endsInHours) {}

    /** An order-sourced entitlement from {@code startsInDays} (default -30) to {@code endsInDays} (or {@code endsInHours}) from now. */
    @PostMapping("/entitlement")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> entitlement(@RequestBody EntitlementRequest r) {
        Instant now = Instant.now();
        Instant from = now.plus(Duration.ofDays(r.startsInDays() == null ? -30 : r.startsInDays()));
        Instant until = r.endsInHours() != null ? now.plus(Duration.ofHours(r.endsInHours())) : now.plus(Duration.ofDays(r.endsInDays() == null ? 30 : r.endsInDays()));
        UUID id = db.jdbc().queryForObject("insert into entitlements(user_id, course_id, valid_from, valid_until, source_order_id) values (?, ?, ?, ?, gen_random_uuid()) returning id",
                UUID.class, r.userId(), r.courseId(), Timestamp.from(from), Timestamp.from(until));
        return Map.of("entitlementId", id.toString());
    }

    record AgedOrder(UUID userId, UUID planId, Integer ageDays) {}

    /** A PENDING_PAYMENT order that was placed {@code ageDays} ago. */
    @PostMapping("/aged-order")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> agedOrder(@RequestBody AgedOrder r) {
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        UUID id = db.jdbc().queryForObject("insert into orders(code, user_id, plan_id, plan_months, amount_vnd, created_at) values (?, ?, ?, 1, 1000, ?) returning id", UUID.class,
                code, r.userId(), r.planId(), Timestamp.from(Instant.now().minus(Duration.ofDays(r.ageDays()))));
        return Map.of("orderId", id.toString(), "code", code);
    }

    record JobRequest(String type) {}

    /** Queues one run of a billing job (expire_orders, expire_entitlements, plan_expiry_reminder) for the real worker. */
    @PostMapping("/run-job")
    Map<String, Object> runJob(@RequestBody JobRequest r) {
        if (!java.util.Set.of("expire_orders", "expire_entitlements", "plan_expiry_reminder").contains(r.type())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "not a billing job");
        }
        tx.executeWithoutResult(s -> jobs.enqueueOnce(r.type(), null, Instant.now(), "e2e:" + UUID.randomUUID()));
        return Map.of("queued", r.type());
    }
}
