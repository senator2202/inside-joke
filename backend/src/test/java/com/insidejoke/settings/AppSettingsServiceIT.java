package com.insidejoke.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

class AppSettingsServiceIT extends AbstractIntegrationTest {

    @Test
    void defaultsComeFromTheMigration() {
        AppSettingsService.Snapshot s = settings.get();
        assertThat(s.freeGamesEnabled()).isTrue();
        assertThat(s.ttsEnabled()).isTrue();
        assertThat(s.dailyFreeAiBudgetMicros()).isEqualTo(50_000_000L);
        assertThat(s.audienceCap()).isEqualTo(2000);
        assertThat(s.drainMode()).isFalse();
    }

    @Test
    void updateValidatesTypeAndRangeAndIsVisibleImmediately() {
        settings.update("audience_cap", json.readTree("150"));
        assertThat(settings.get().audienceCap()).isEqualTo(150);
        assertThat(jdbc.sql("SELECT value::text FROM app_setting WHERE key = 'audience_cap'")
                        .query(String.class)
                        .single())
                .isEqualTo("150");

        assertThatThrownBy(() -> settings.update("audience_cap", json.readTree("-1")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> settings.update("audience_cap", json.readTree("\"many\"")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> settings.update("drain_mode", json.readTree("1")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> settings.update("no_such_flag", json.readTree("true")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(settings.get().audienceCap()).isEqualTo(150);
    }

    @Test
    void manualDatabaseEditIsPickedUpOnRefresh() {
        jdbc.sql("UPDATE app_setting SET value = 'true'::jsonb WHERE key = 'drain_mode'")
                .update();
        assertThat(settings.get().drainMode()).isFalse();
        settings.refresh();
        assertThat(settings.get().drainMode()).isTrue();
    }
}
