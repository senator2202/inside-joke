package com.insidejoke.settings;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Flags and limits from app_setting. Reads come from an in-memory snapshot (hot paths such as joining a room
 * read it), refreshed after every change and every 15 seconds so a manual SQL edit also takes effect.
 */
@Service
public class AppSettingsService {

    /** Immutable view of all settings at one moment. */
    public record Snapshot(
            boolean freeGamesEnabled,
            boolean ttsEnabled,
            long dailyFreeAiBudgetMicros,
            int audienceCap,
            boolean drainMode) {

        public Map<String, Object> asMap() {
            return Map.of(
                    SettingKey.FREE_GAMES_ENABLED.key(), freeGamesEnabled,
                    SettingKey.TTS_ENABLED.key(), ttsEnabled,
                    SettingKey.DAILY_FREE_AI_BUDGET_MICROS.key(), dailyFreeAiBudgetMicros,
                    SettingKey.AUDIENCE_CAP.key(), audienceCap,
                    SettingKey.DRAIN_MODE.key(), drainMode);
        }
    }

    /** Published after an admin changes a setting. */
    public record Changed(SettingKey key, Object value) {}

    private static final Snapshot DEFAULTS = new Snapshot(true, true, 50_000_000L, 2000, false);
    private static final Logger log = LoggerFactory.getLogger(AppSettingsService.class);

    private final AppSettingRepository repository;
    private final JsonMapper json;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private volatile Snapshot current = DEFAULTS;

    public AppSettingsService(
            AppSettingRepository repository, JsonMapper json, Clock clock, ApplicationEventPublisher events) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
        this.events = events;
    }

    @PostConstruct
    void load() {
        refresh();
    }

    public Snapshot get() {
        return current;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public void refresh() {
        List<Map.Entry<String, String>> rows = List.copyOf(repository.findAll().entrySet());
        Map<SettingKey, JsonNode> values = new EnumMap<>(SettingKey.class);
        for (Map.Entry<String, String> row : rows) {
            Optional<SettingKey> key = SettingKey.fromKey(row.getKey());
            if (key.isPresent()) {
                values.put(key.get(), json.readTree(row.getValue()));
            } else {
                log.warn("Ignoring unknown app_setting '{}'", row.getKey());
            }
        }
        current = new Snapshot(
                bool(values.get(SettingKey.FREE_GAMES_ENABLED), DEFAULTS.freeGamesEnabled()),
                bool(values.get(SettingKey.TTS_ENABLED), DEFAULTS.ttsEnabled()),
                number(values.get(SettingKey.DAILY_FREE_AI_BUDGET_MICROS), DEFAULTS.dailyFreeAiBudgetMicros()),
                (int) number(values.get(SettingKey.AUDIENCE_CAP), DEFAULTS.audienceCap()),
                bool(values.get(SettingKey.DRAIN_MODE), DEFAULTS.drainMode()));
    }

    /** Validates the JSON value against the key's type and range, stores it and refreshes the snapshot. */
    public Snapshot update(String rawKey, JsonNode value) {
        SettingKey key = SettingKey.fromKey(rawKey)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Unknown setting '" + rawKey + "'."));
        Object parsed = validate(key, value);
        repository.upsert(key.key(), json.writeValueAsString(parsed), clock.instant());
        refresh();
        log.info("Setting {} changed to {}", key.key(), parsed);
        events.publishEvent(new Changed(key, parsed));
        return current;
    }

    private static Object validate(SettingKey key, JsonNode value) {
        if (key.kind() == SettingKey.Kind.BOOLEAN) {
            if (value == null || !value.isBoolean()) {
                throw invalid(key, "must be true or false");
            }
            return value.booleanValue();
        }
        if (value == null || !value.isIntegralNumber()) {
            throw invalid(key, "must be a whole number");
        }
        long n = value.longValue();
        if (n < 0 || n > key.max()) {
            throw invalid(key, "must be between 0 and " + key.max());
        }
        return n;
    }

    private static ApiException invalid(SettingKey key, String reason) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED, key.key() + " " + reason + ".", Map.of("fields", Map.of("value", reason)));
    }

    private static boolean bool(JsonNode node, boolean fallback) {
        return node != null && node.isBoolean() ? node.booleanValue() : fallback;
    }

    private static long number(JsonNode node, long fallback) {
        return node != null && node.isIntegralNumber() ? node.longValue() : fallback;
    }
}
