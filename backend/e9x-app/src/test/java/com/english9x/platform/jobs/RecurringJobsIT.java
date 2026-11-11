package com.english9x.platform.jobs;

import static com.english9x.platform.jobs.JobTestSupport.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.english9x.platform.jobs.api.RecurringJob;
import com.english9x.support.MutableClock;
import com.english9x.support.TestPostgres;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Periodic jobs: one tick per time bucket however many instances try, the queue's own retention, and the narrow worker door of the SYSTEM audit actor. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"e9x.jobs.poll-interval=1h", "e9x.jobs.retention=7d"})
@Import({JobTestSupport.class, RecurringJobsIT.Schedules.class})
class RecurringJobsIT {

    static final String DB = TestPostgres.createDatabase();

    @TestConfiguration
    static class Schedules {
        @Bean
        RecurringJob testSchedule() {
            return new RecurringJob("test_ok", Duration.ofHours(1));
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired RecurringJobScheduler scheduler;
    @Autowired MutableClock clock;
    @Autowired org.springframework.context.ApplicationContext ctx;
    final JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));

    @BeforeEach
    void clean() {
        su.update("delete from jobs");
        clock.set(T0);
    }

    private int ticks() {
        return su.queryForObject("select count(*) from jobs where type = 'test_ok' and dedupe_key like 'tick:%'", Integer.class);
    }

    @Test
    void aBucketIsQueuedOnceHoweverManyInstancesTry() throws Exception {
        int a = scheduler.tick();
        assertEquals(8, a, "test_ok, the three billing jobs, the purge, release_expired_claim (P4.3), assignment_due_soon (P6.2) and session_reminder (P6.3)");
        assertEquals(0, scheduler.tick(), "same bucket: nothing new");
        assertEquals(1, ticks());
        su.update("delete from jobs");
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return scheduler.tick();
            }));
        }
        go.countDown();
        int sum = 0;
        for (Future<Integer> f : fs) {
            sum += f.get();
        }
        pool.shutdown();
        assertEquals(1, ticks(), "six racing instances, one tick");
        int perBucket = a; // every recurring job of the application (billing's three, the purge, the test one) is queued once per bucket
        assertEquals(perBucket, sum, "six racing instances queue each job once between them");
        assertEquals(perBucket, su.queryForObject("select count(*) from jobs where dedupe_key like 'tick:%'", Integer.class));
        clock.advance(Duration.ofHours(1));
        assertEquals(perBucket, scheduler.tick(), "the next bucket is a new tick");
        assertEquals(2, ticks());
    }

    @Test
    void purgeDeletesOnlyOldFinishedJobs() {
        java.time.Instant real = java.time.Instant.now(); // the database's own "a day old" floor runs on its real clock
        clock.set(real.plus(Duration.ofDays(30)));
        UUID old = job("SUCCEEDED", real.minus(Duration.ofDays(10)));
        UUID oldFailed = job("FAILED", real.minus(Duration.ofDays(10)));
        UUID recent = job("SUCCEEDED", real.plus(Duration.ofDays(28)));
        UUID queued = job("QUEUED", null);
        su.update("update jobs set type = 'test_ok', run_at = now() + interval '100 years' where id = ?", queued);
        su.update("delete from jobs where id not in (?,?,?,?)", old, oldFailed, recent, queued);
        UUID purge = UUID.randomUUID();
        su.update("insert into jobs(id,type,payload,status,run_at) values (?, 'purge_finished_jobs', '{}', 'QUEUED', ?)", purge, Timestamp.from(real.plus(Duration.ofDays(30))));
        ctx.getBean(JobWorker.class).pollOnce();
        List<UUID> left = su.queryForList("select id from jobs", UUID.class);
        assertEquals(java.util.Set.of(recent, queued, purge), new java.util.HashSet<>(left));
    }

    @Test
    void theDatabaseRefusesToPurgeAFreshFinishedJob() {
        UUID fresh = UUID.randomUUID();
        su.update("insert into jobs(id,type,payload,status,run_at,finished_at,attempts) values (?, 'x', '{}', 'SUCCEEDED', now(), now(), 1)", fresh);
        JdbcTemplate worker = new JdbcTemplate(TestPostgres.worker(DB));
        assertEquals(0, worker.update("delete from jobs where id = ?", fresh), "policy: only jobs finished more than a day ago");
    }

    @Test
    void onlyTheWorkerCanWriteSystemAuditAndOnlyListedActions() {
        JdbcTemplate worker = new JdbcTemplate(TestPostgres.worker(DB));
        UUID t = UUID.randomUUID();
        worker.queryForObject("select count(*) from (select audit_log_record_system('order.expire','order',?,'{}'::jsonb)) r", Integer.class, t.toString());
        assertEquals("SYSTEM", su.queryForObject("select actor_kind from audit_log where target_id = ?", String.class, t.toString()));
        assertThrows(DataAccessException.class, () -> worker.queryForObject("select count(*) from (select audit_log_record_system('org.create','order','x','{}'::jsonb)) r", Integer.class));
        assertThrows(DataAccessException.class, () -> new JdbcTemplate(TestPostgres.app(DB)).queryForObject("select count(*) from (select audit_log_record_system('order.expire','order','x','{}'::jsonb)) r", Integer.class));
        assertThrows(DataAccessException.class, () -> worker.update("insert into audit_log(actor_kind,action,target_type,target_id) values ('SYSTEM','order.expire','order','y')"));
    }

    private UUID job(String status, java.time.Instant finished) {
        UUID id = UUID.randomUUID();
        su.update("insert into jobs(id,type,payload,status,run_at,finished_at,attempts) values (?, 'x', '{\"p\":1}', ?, now(), ?, ?)", id, status,
                finished == null ? null : Timestamp.from(finished), finished == null ? 0 : 1);
        return id;
    }
}
