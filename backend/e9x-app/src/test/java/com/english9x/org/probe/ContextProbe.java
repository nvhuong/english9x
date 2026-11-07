package com.english9x.org.probe;

import com.english9x.org.api.CurrentContextHolder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint for ContextSwitchIsolationIT (test sources, profile {@code ctx-probe}: never in the production jar or
 * its OpenAPI). It reads the classes visible through Row-Level Security TWICE inside one transaction, and between the two
 * reads it waits on a gate the test controls (no sleeps): so the test can send other requests, in other contexts, while this
 * one is in flight.
 */
@RestController
@Profile("ctx-probe")
public class ContextProbe {

    /** One gate per scenario: {@code arrived} counts requests inside the transaction, {@code proceed} lets them continue. */
    public static final class Gate {
        public final CountDownLatch arrived;
        public final CountDownLatch proceed = new CountDownLatch(1);
        final CyclicBarrier barrier;

        Gate(int parties) {
            this.arrived = new CountDownLatch(parties);
            this.barrier = new CyclicBarrier(Math.max(parties, 1));
        }
    }

    static final Map<String, Gate> GATES = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;

    ContextProbe(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/probe/classes")
    @Transactional(readOnly = true)
    Map<String, Object> classes(@RequestHeader(value = "X-Probe-Gate", required = false) String gate,
                                @RequestHeader(value = "X-Probe-Mode", defaultValue = "none") String mode) throws Exception {
        List<String> before = jdbc.queryForList("select name from classes order by name", String.class);
        String settingBefore = jdbc.queryForObject("select coalesce(current_setting('app.org_id', true), '')", String.class);
        if (gate != null) {
            Gate g = GATES.get(gate);
            g.arrived.countDown();
            switch (mode) {
                case "hold" -> {
                    if (!g.proceed.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("gate never opened");
                    }
                }
                case "barrier" -> g.barrier.await(30, TimeUnit.SECONDS);
                default -> { }
            }
        }
        List<String> after = jdbc.queryForList("select name from classes order by name", String.class);
        String settingAfter = jdbc.queryForObject("select coalesce(current_setting('app.org_id', true), '')", String.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("before", before);
        out.put("after", after);
        out.put("settingBefore", settingBefore);
        out.put("settingAfter", settingAfter);
        out.put("boundOrg", CurrentContextHolder.current().flatMap(CurrentContextHolder.Binding::orgId).map(UUID::toString).orElse(""));
        out.put("thread", Thread.currentThread().getName());
        return out;
    }
}
