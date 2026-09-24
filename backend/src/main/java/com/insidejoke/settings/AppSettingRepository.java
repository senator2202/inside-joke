package com.insidejoke.settings;

import com.insidejoke.common.DbUtils;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Runtime flags stored in {@code app_setting} as JSON values. */
@Repository
public class AppSettingRepository {

    private final JdbcClient jdbc;

    public AppSettingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every stored setting: key to its JSON text. */
    public Map<String, String> findAll() {
        Map<String, String> values = new LinkedHashMap<>();
        jdbc.sql("SELECT key, value::text AS value FROM app_setting")
                .query((rs, n) -> values.put(rs.getString("key"), rs.getString("value")))
                .list();
        return values;
    }

    public void upsert(String key, String jsonValue, Instant now) {
        jdbc.sql("INSERT INTO app_setting (key, value, updated_at) VALUES (?, CAST(? AS jsonb), ?) "
                        + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at")
                .params(key, jsonValue, DbUtils.ts(now))
                .update();
    }
}
