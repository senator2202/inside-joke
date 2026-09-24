package com.insidejoke.settings;

import java.util.Arrays;
import java.util.Optional;

/** Runtime flags and limits stored in app_setting and editable by admins without a deploy. */
public enum SettingKey {
    FREE_GAMES_ENABLED("free_games_enabled", Kind.BOOLEAN, 0, 0),
    TTS_ENABLED("tts_enabled", Kind.BOOLEAN, 0, 0),
    DAILY_FREE_AI_BUDGET_MICROS("daily_free_ai_budget_micros", Kind.INTEGER, 0, 10_000_000_000L),
    AUDIENCE_CAP("audience_cap", Kind.INTEGER, 0, 20_000),
    DRAIN_MODE("drain_mode", Kind.BOOLEAN, 0, 0);

    public enum Kind {
        BOOLEAN,
        INTEGER
    }

    private final String key;
    private final Kind kind;
    private final long min;
    private final long max;

    SettingKey(String key, Kind kind, long min, long max) {
        this.key = key;
        this.kind = kind;
        this.min = min;
        this.max = max;
    }

    public String key() {
        return key;
    }

    public Kind kind() {
        return kind;
    }

    public long min() {
        return min;
    }

    public long max() {
        return max;
    }

    public static Optional<SettingKey> fromKey(String key) {
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }
}
