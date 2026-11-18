package com.english9x.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock tests move by hand, so retry delays and leases are checked without sleeping. Instants are kept at microsecond precision, the
 * precision PostgreSQL stores, so a value read back from the database equals the one the clock gave.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    public MutableClock(Instant start) {
        this.now = new AtomicReference<>(start.truncatedTo(ChronoUnit.MICROS));
    }

    public void set(Instant instant) {
        now.set(instant.truncatedTo(ChronoUnit.MICROS));
    }

    public void advance(Duration d) {
        now.updateAndGet(i -> i.plus(d));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
