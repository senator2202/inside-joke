package com.insidejoke.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.Party;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeedbackIT extends AbstractIntegrationTest {

    @Test
    void theHostRatesTheirOwnGameOnce() {
        var host = Party.signIn(port, json, FAKE, uniqueEmail("rate"));
        UUID hostId = UUID.fromString(host.get("/api/me").json().path("id").asString());
        UUID game = data.game(hostId, null, clock.instant());
        String path = "/api/games/" + game + "/feedback";
        assertThat(host.post(path, Map.of("rating", 6)).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(host.post(path, Map.of("rating", 4, "comment", "x".repeat(501)))
                        .errorCode())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(host.post(path, Map.of("rating", 4, "comment", "Loved it")).status())
                .isEqualTo(204);
        assertThat(host.post(path, Map.of("rating", 5)).status()).isEqualTo(204);
        Map<String, Object> row = jdbc.sql("SELECT rating, comment FROM game_feedback WHERE game_session_id = ?")
                .param(game)
                .query()
                .singleRow();
        assertThat(((Number) row.get("rating")).intValue()).isEqualTo(5);
        assertThat(row.get("comment")).isNull();

        var stranger = Party.signIn(port, json, FAKE, uniqueEmail("other"));
        assertThat(stranger.post(path, Map.of("rating", 1)).errorCode()).isEqualTo("FORBIDDEN");
        assertThat(host.post("/api/games/" + UUID.randomUUID() + "/feedback", Map.of("rating", 3))
                        .errorCode())
                .isEqualTo("NOT_FOUND");
        assertThat(client().post(path, Map.of("rating", 3)).status()).isEqualTo(401);
    }
}
