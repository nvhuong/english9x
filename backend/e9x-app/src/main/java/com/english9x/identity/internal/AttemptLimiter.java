package com.english9x.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sliding-window counter of attempts per key, in memory. The deployment is ONE app instance on one VPS, so an
 * in-process structure is enough (a second instance would need this in the database / Redis; recorded in the P1.4
 * report). Bounded: at most {@code maxKeys} keys, the least recently used is evicted first, expired ones are dropped
 * as they are met, so memory cannot grow with attacker-chosen emails.
 */
final class AttemptLimiter {

    private static final Pattern SPEC = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)\\s*([smhd])");

    private final int max;
    private final Duration window;
    private final int maxKeys;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures;

    /** @param spec "10/15m": at most 10 failures per 15 minutes */
    AttemptLimiter(String spec, Clock clock) {
        this(spec, clock, 20_000);
    }

    AttemptLimiter(String spec, Clock clock, int maxKeys) {
        Matcher m = SPEC.matcher(spec.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("bad rate limit '" + spec + "', expected <count>/<n><s|m|h|d> such as 10/15m");
        }
        this.max = Integer.parseInt(m.group(1));
        long n = Long.parseLong(m.group(2));
        this.window = switch (m.group(3)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
        this.maxKeys = maxKeys;
        this.clock = clock;
        this.failures = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> eldest) {
                return size() > AttemptLimiter.this.maxKeys;
            }
        };
    }

    /**
     * Atomically takes one attempt from {@code key}'s allowance, or returns false when it is used up. Call it BEFORE the
     * work being limited: checking first and counting after a slow step (bcrypt) would let every parallel request
     * through the check. A success gives the allowance back with {@link #reset}.
     */
    synchronized boolean tryAcquire(String key) {
        if (prune(key) >= max) {
            return false;
        }
        failures.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(clock.instant());
        return true;
    }

    /** Seconds until the oldest counted failure leaves the window (at least 1). */
    synchronized long retryAfterSeconds(String key) {
        Deque<Instant> d = failures.get(key);
        if (d == null || d.isEmpty()) {
            return 1;
        }
        long s = Duration.between(clock.instant(), d.peekFirst().plus(window)).toSeconds() + 1;
        return Math.max(1, s);
    }

    synchronized void reset(String key) {
        failures.remove(key);
    }

    synchronized int size() {
        return failures.size();
    }

    private int prune(String key) {
        Deque<Instant> d = failures.get(key);
        if (d == null) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(window);
        for (Iterator<Instant> it = d.iterator(); it.hasNext(); ) {
            if (it.next().isAfter(cutoff)) {
                break;
            }
            it.remove();
        }
        if (d.isEmpty()) {
            failures.remove(key);
            return 0;
        }
        return d.size();
    }
}
