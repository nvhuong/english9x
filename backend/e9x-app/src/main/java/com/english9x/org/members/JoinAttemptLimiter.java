package com.english9x.org.members;

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
 * Sliding-window attempt counter per key, in memory, bounded (LRU). Same pattern as {@code identity.internal.AttemptLimiter} (which the org module may
 * not use: it is internal to identity), plus {@link #refund}: a successful join gives its attempt back, so a classroom behind one address is not
 * locked out by its own success while every wrong guess still counts. One app instance on one VPS, like the login limiter.
 */
final class JoinAttemptLimiter {

    private static final Pattern SPEC = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)\\s*([smhd])");

    private final int max;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> attempts;

    JoinAttemptLimiter(String spec, Clock clock, int maxKeys) {
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
        this.clock = clock;
        this.attempts = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> eldest) {
                return size() > maxKeys;
            }
        };
    }

    /** Takes one attempt from the key's allowance atomically; false when used up. Call BEFORE the work. */
    synchronized boolean tryAcquire(String key) {
        if (prune(key) >= max) {
            return false;
        }
        attempts.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(clock.instant());
        return true;
    }

    /** Gives back the most recent attempt of the key. */
    synchronized void refund(String key) {
        Deque<Instant> d = attempts.get(key);
        if (d != null) {
            d.pollLast();
            if (d.isEmpty()) {
                attempts.remove(key);
            }
        }
    }

    synchronized long retryAfterSeconds(String key) {
        Deque<Instant> d = attempts.get(key);
        if (d == null || d.isEmpty()) {
            return 1;
        }
        return Math.max(1, Duration.between(clock.instant(), d.peekFirst().plus(window)).toSeconds() + 1);
    }

    private int prune(String key) {
        Deque<Instant> d = attempts.get(key);
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
            attempts.remove(key);
            return 0;
        }
        return d.size();
    }
}
