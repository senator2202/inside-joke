package com.insidejoke.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.ApiClient;
import com.insidejoke.support.Party;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PassControllerIT extends AbstractIntegrationTest {

    @Test
    void reportsWhatTheHostCanPlayNext() {
        ApiClient host = Party.signIn(port, json, FAKE, uniqueEmail("ent"));
        UUID id = UUID.fromString(host.get("/api/me").json().path("id").asString());
        JsonNode fresh = host.get("/api/billing/passes").json();
        assertThat(fresh.path("access").path("nextGame").asString()).isEqualTo("FREE");
        assertThat(fresh.path("access").path("freeGameAvailable").asBoolean()).isTrue();
        assertThat(fresh.path("drainMode").asBoolean()).isFalse();

        data.game(id, null, clock.instant());
        JsonNode used = host.get("/api/billing/passes").json();
        assertThat(used.path("access").path("nextGame").asString()).isEqualTo("PAYWALL");
        assertThat(used.path("access").path("paywallReason").asString()).isEqualTo("PAYWALL_FREE_LIMIT");
        assertThat(used.path("access").path("nextFreeGameAt").isString()).isTrue();

        data.pass(id, Product.HOST_PASS);
        JsonNode paid = host.get("/api/billing/passes").json();
        assertThat(paid.path("access").path("nextGame").asString()).isEqualTo("HOST_PASS");
        assertThat(paid.path("access")
                        .path("passes")
                        .get(0)
                        .path("gamesLeftThisMonth")
                        .asInt())
                .isEqualTo(15);
        assertThat(client().get("/api/billing/passes").status()).isEqualTo(401);
    }

    @Test
    void showsAPassBoughtAheadSeparatelyWithItsStart() {
        ApiClient host = Party.signIn(port, json, FAKE, uniqueEmail("ahead"));
        UUID id = UUID.fromString(host.get("/api/me").json().path("id").asString());
        EntitlementEntity running = data.pass(id, Product.HOST_PASS);
        EntitlementEntity ahead = data.pass(id, Product.HOST_PASS, running.endsAt());
        EntitlementEntity revoked =
                data.pass(id, Product.PARTY_PASS, clock.instant().plus(Duration.ofDays(3)));
        jdbc.sql("UPDATE entitlement SET revoked_at = now(), revoke_reason = 'ADMIN' WHERE id = ?")
                .param(revoked.id())
                .update();

        JsonNode access = host.get("/api/billing/passes").json().path("access");

        assertThat(access.path("passes").size())
                .as("only the running pass gives access")
                .isEqualTo(1);
        assertThat(access.path("passes").get(0).path("id").asString())
                .isEqualTo(running.id().toString());
        JsonNode upcoming = access.path("upcomingPasses");
        assertThat(upcoming.size())
                .as("a revoked pass is not waiting for anything")
                .isEqualTo(1);
        assertThat(upcoming.get(0).path("id").asString()).isEqualTo(ahead.id().toString());
        assertThat(Instant.parse(upcoming.get(0).path("startsAt").asString())).isEqualTo(running.endsAt());
        assertThat(upcoming.get(0).path("gamesLeftThisMonth").asInt()).isEqualTo(15);
        assertThat(access.path("nextGame").asString()).isEqualTo("HOST_PASS");
    }
}
