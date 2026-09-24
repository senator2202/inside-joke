package com.insidejoke.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that stands still until a test moves it: timers are tested without waiting in real time. */
public final class ManualClock extends Clock {

    private volatile Instant now;

    public ManualClock(Instant start) {
        this.now = start;
    }

    public ManualClock() {
        this(Instant.parse("2026-09-25T19:00:00Z"));
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
        return now;
    }

    public void advance(Duration by) {
        now = now.plus(by);
    }

    /** Moves the clock to {@code millis}; never backwards. */
    public void setMillis(long millis) {
        if (millis > now.toEpochMilli()) {
            now = Instant.ofEpochMilli(millis);
        }
    }
}
