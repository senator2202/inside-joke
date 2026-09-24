package com.insidejoke.ai;

import com.insidejoke.common.TokenUtils;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Host voice clips in memory for 10 minutes (blueprint 8: GET /api/voice-lines/{id}). Ids are unguessable.
 * Identical texts share one clip, so canned lobby lines are synthesised once, not once per room.
 */
@Service
public class VoiceLineCacheService {

    static final Duration TTL = Duration.ofMinutes(10);
    static final long MAX_BYTES = 256L * 1024 * 1024;

    private record Clip(byte[] mp3, Instant expiresAt) {}

    private final Map<String, Clip> clips = new ConcurrentHashMap<>();
    private final Map<String, String> byText = new ConcurrentHashMap<>();
    private final AtomicLong bytes = new AtomicLong();
    private final Clock clock;

    public VoiceLineCacheService(Clock clock) {
        this.clock = clock;
    }

    public String put(String textKey, byte[] mp3) {
        String id = TokenUtils.random(16);
        clips.put(id, new Clip(mp3, clock.instant().plus(TTL)));
        bytes.addAndGet(mp3.length);
        byText.put(textKey, id);
        if (bytes.get() > MAX_BYTES) {
            evictOldest();
        }
        return id;
    }

    /** An existing clip for the same text, refreshed so it lives another 10 minutes. */
    public Optional<String> forText(String textKey) {
        String id = byText.get(textKey);
        if (id == null) {
            return Optional.empty();
        }
        Clip clip = clips.computeIfPresent(
                id, (k, c) -> new Clip(c.mp3(), clock.instant().plus(TTL)));
        return clip == null ? Optional.empty() : Optional.of(id);
    }

    public Optional<byte[]> get(String id) {
        Clip clip = clips.get(id);
        return clip == null || clip.expiresAt().isBefore(clock.instant()) ? Optional.empty() : Optional.of(clip.mp3());
    }

    @Scheduled(fixedDelay = 60_000)
    public void expire() {
        Instant now = clock.instant();
        clips.entrySet().removeIf(e -> {
            if (e.getValue().expiresAt().isBefore(now)) {
                bytes.addAndGet(-e.getValue().mp3().length);
                return true;
            }
            return false;
        });
        byText.values().removeIf(id -> !clips.containsKey(id));
    }

    private void evictOldest() {
        clips.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().expiresAt()))
                .limit(Math.max(1, clips.size() / 4))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(id -> {
                    Clip c = clips.remove(id);
                    if (c != null) {
                        bytes.addAndGet(-c.mp3().length);
                    }
                });
    }
}
