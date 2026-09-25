package com.insidejoke.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock that stands still until a test moves it: timers are tested without waiting in real time. */
public final class ManualClock extends Clock {

    private final AtomicReference<Instant> now;

    public ManualClock(Instant start) {
        this.now = new AtomicReference<>(start);
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
        return now.get();
    }

    public void advance(Duration by) {
        now.updateAndGet(t -> t.plus(by));
    }

    /** Moves the clock to {@code millis}; never backwards. */
    public void setMillis(long millis) {
        now.updateAndGet(t -> millis > t.toEpochMilli() ? Instant.ofEpochMilli(millis) : t);
    }
}
