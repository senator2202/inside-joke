package com.insidejoke.web;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * In-memory keyed rate limits (Bucket4j). One server means no distributed limiter is needed.
 */
@Service
public class RateLimitService {

    /** A named limit: {@code capacity} requests per {@code period}. */
    public record Limit(String name, long capacity, Duration period) {}

    public static final Limit MAGIC_LINK_PER_EMAIL = new Limit("ml-email", 5, Duration.ofHours(1));
    public static final Limit MAGIC_LINK_PER_IP = new Limit("ml-ip", 20, Duration.ofHours(1));
    public static final Limit CODE_VERIFY_PER_IP = new Limit("code-ip", 30, Duration.ofMinutes(10));
    public static final Limit ROOM_JOIN_PER_IP = new Limit("join-ip", 20, Duration.ofMinutes(1));
    public static final Limit ROOM_LOOKUP_PER_IP = new Limit("lookup-ip", 60, Duration.ofMinutes(1));
    public static final Limit ROOM_CREATE_PER_USER = new Limit("create-user", 20, Duration.ofHours(1));
    public static final Limit CHECKOUT_PER_USER = new Limit("checkout-user", 20, Duration.ofHours(1));
    public static final Limit EVENTS_PER_IP = new Limit("events-ip", 120, Duration.ofMinutes(1));

    private record Entry(Bucket bucket, long[] lastUsed) {}

    private final Map<String, Entry> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimitService(Clock clock) {
        this.clock = clock;
    }

    public boolean tryConsume(Limit limit, String key) {
        Entry entry = buckets.computeIfAbsent(limit.name() + ':' + key, k -> new Entry(newBucket(limit), new long[1]));
        entry.lastUsed()[0] = clock.millis();
        return entry.bucket().tryConsume(1);
    }

    /** Throws RATE_LIMITED when the limit is exhausted. */
    public void check(Limit limit, String key) {
        if (!tryConsume(limit, key)) {
            throw new ApiException(ErrorCode.RATE_LIMITED);
        }
    }

    /** Creates an unshared bucket, for example per WebSocket connection. */
    public static Bucket newBucket(Limit limit) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period())
                        .build())
                .build();
    }

    @Scheduled(fixedDelay = 600_000)
    public void evictIdle() {
        long cutoff = clock.millis() - Duration.ofHours(2).toMillis();
        buckets.entrySet().removeIf(e -> e.getValue().lastUsed()[0] < cutoff);
    }

    /** Clears all buckets; used by tests. */
    public void reset() {
        buckets.clear();
    }
}
