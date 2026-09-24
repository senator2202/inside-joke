package com.insidejoke.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Real time plus an adjustable offset, so tests can jump days ahead without sleeping. */
public final class TestClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

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
        return Instant.now().plus(offset);
    }

    public void advance(Duration by) {
        offset = offset.plus(by);
    }

    /** Moves the clock so that "now" is the given instant. */
    public void setNow(Instant now) {
        offset = Duration.between(Instant.now(), now);
    }

    public void reset() {
        offset = Duration.ZERO;
    }
}
