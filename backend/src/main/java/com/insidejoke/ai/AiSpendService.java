package com.insidejoke.ai;

import com.insidejoke.settings.AppSettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/**
 * Today's AI spend on free games (UTC day), cached for 60 seconds as the blueprint prescribes.
 * Guards the owner's budget against a viral spike: once exceeded, new free games pause, paid games continue.
 */
@Service
public class AiSpendService {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private record Cached(Instant loadedAt, LocalDate day, long micros) {}

    private final AiCallRepository calls;
    private final AppSettingsService settings;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Cached cached;

    public AiSpendService(AiCallRepository calls, AppSettingsService settings, Clock clock) {
        this.calls = calls;
        this.settings = settings;
        this.clock = clock;
    }

    public long freeSpendTodayMicros() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Cached c = cached;
        if (c != null && c.day().equals(today) && c.loadedAt().plus(CACHE_TTL).isAfter(now)) {
            return c.micros();
        }
        lock.lock();
        try {
            c = cached;
            if (c != null
                    && c.day().equals(today)
                    && c.loadedAt().plus(CACHE_TTL).isAfter(now)) {
                return c.micros();
            }
            long micros =
                    calls.freeGameCostSince(today.atStartOfDay(ZoneOffset.UTC).toInstant());
            cached = new Cached(now, today, micros);
            return micros;
        } finally {
            lock.unlock();
        }
    }

    public boolean freeBudgetExhausted() {
        return freeSpendTodayMicros() >= settings.get().dailyFreeAiBudgetMicros();
    }

    /** Adds a just-recorded free-game cost to the cached figure so a spike is noticed before the cache expires. */
    public void addFreeSpend(long micros) {
        lock.lock();
        try {
            Cached c = cached;
            if (c != null) {
                cached = new Cached(c.loadedAt(), c.day(), c.micros() + micros);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Drops the cache; used after admin changes and in tests. */
    public void invalidate() {
        cached = null;
    }
}
