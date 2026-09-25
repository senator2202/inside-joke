package com.insidejoke.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Real time plus an adjustable offset, so tests can jump days ahead without sleeping. */
public final class TestClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

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
        return Instant.now().plus(offset.get());
    }

    public void advance(Duration by) {
        offset.updateAndGet(o -> o.plus(by));
    }

    /** Moves the clock so that "now" is the given instant. */
    public void setNow(Instant now) {
        offset.set(Duration.between(Instant.now(), now));
    }

    public void reset() {
        offset.set(Duration.ZERO);
    }
}
