package com.english9x.billing;

import com.english9x.org.OrgIT;
import com.english9x.platform.jobs.api.JobQueue;
import com.english9x.support.MutableClock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Harness of the billing tests: OrgIT plus a movable application clock and a fast real worker (jobs are run by the real worker thread). */
@Import(BillingIT.Clocks.class)
@TestPropertySource(properties = {"e9x.jobs.poll-interval=100ms", "e9x.billing.order-expiry=7d", "e9x.billing.expiry-reminder=7d"})
public abstract class BillingIT extends OrgIT {

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock billingClock() {
            return new MutableClock(Instant.now());
        }
    }

    @Autowired protected MutableClock clock;
    @Autowired protected JobQueue queue;
    @Autowired protected PlatformTransactionManager tm;

    protected UUID plan(long price, int months, int max, boolean active) {
        UUID id = UUID.randomUUID();
        su.update("insert into plans(id,name,price_vnd,months,max_courses,active) values (?,?,?,?,?,?)", id, "Plan " + id.toString().substring(0, 6), price, months, max, active);
        return id;
    }

    protected UUID course(String status) {
        UUID id = UUID.randomUUID();
        su.update("insert into exams(code,name) values ('IELTS','IELTS') on conflict do nothing");
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        su.update("insert into courses(id,owner_org_id,exam_code,code,title,status) values (?,null,'IELTS',?,'Course',?)", id, code, status);
        return id;
    }

    /** Queues one tick of a job type and waits for the real worker to finish it. */
    protected void runJob(String type) {
        String key = "it:" + UUID.randomUUID();
        new TransactionTemplate(tm).execute(s -> queue.enqueueOnce(type, null, clock.instant(), key));
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            String st = su.queryForObject("select status from jobs where type = ? and dedupe_key = ?", String.class, type, key);
            if ("SUCCEEDED".equals(st)) {
                return;
            }
            if ("FAILED".equals(st)) {
                throw new AssertionError(type + " failed: " + su.queryForObject("select last_error from jobs where type = ? and dedupe_key = ?", String.class, type, key));
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError(type + " did not finish");
    }
}
