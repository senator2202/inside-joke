package com.insidejoke.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.billing.Product;
import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.ApiClient;
import com.insidejoke.support.Await;
import com.insidejoke.support.FakeAi;
import com.insidejoke.support.Party;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountIT extends AbstractIntegrationTest {

    @Test
    void longEmailAddressesCanSignIn() {
        String email = "a.very.long.mailbox.name." + UUID.randomUUID() + "@subdomain.example-company.com";
        ApiClient host = Party.signIn(port, json, FAKE, email);
        assertThat(host.get("/api/me").json().path("email").asString()).isEqualTo(email);
        assertThat(jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = ?")
                        .param(host.get("/api/me").json().path("id").asString())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void deletingTheAccountAnonymizesEndsPassesClosesRoomsAndSignsOutEverywhere() {
        FakeAi.install(FAKE);
        try (Party party = Party.create(port, json, FAKE, 1)) {
            String email = party.host.get("/api/me").json().path("email").asString();
            UUID id =
                    UUID.fromString(party.host.get("/api/me").json().path("id").asString());
            data.pass(id, Product.HOST_PASS);
            ApiClient laptop = Party.signIn(port, json, FAKE, email);
            assertThat(laptop.get("/api/me").status()).isEqualTo(200);

            assertThat(party.host.delete("/api/me").status()).isEqualTo(204);

            assertThat(party.host.get("/api/me").status()).isEqualTo(401);
            assertThat(laptop.get("/api/me").status())
                    .as("other devices are signed out too")
                    .isEqualTo(401);
            assertThat(jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = ?")
                            .param(id.toString())
                            .query(Integer.class)
                            .single())
                    .isZero();
            Map<String, Object> row = jdbc.sql(
                            "SELECT email, display_name, google_sub, deleted_at FROM app_user WHERE id = ?")
                    .param(id)
                    .query()
                    .singleRow();
            assertThat(row.get("email").toString()).isEqualTo("deleted+" + id + "@users.invalid");
            assertThat(row.get("deleted_at")).isNotNull();
            assertThat(jdbc.sql("SELECT count(*) FROM entitlement WHERE user_id = ? AND revoked_at IS NULL")
                            .param(id)
                            .query(Integer.class)
                            .single())
                    .isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM purchase WHERE user_id = ?")
                            .param(id)
                            .query(Integer.class)
                            .single())
                    .as("purchases stay for accounting")
                    .isEqualTo(1);
            Await.until("room closed", () -> party.phones.get(0).socket().received("closed"));

            ApiClient again = Party.signIn(port, json, FAKE, email);
            assertThat(UUID.fromString(again.get("/api/me").json().path("id").asString()))
                    .as("a fresh account")
                    .isNotEqualTo(id);
        }
    }

    @Test
    void deletingRequiresASession() {
        assertThat(client().delete("/api/me").status()).isEqualTo(401);
    }

    @Test
    void publicStatusShowsMaintenanceWithoutSigningIn() {
        assertThat(client().get("/api/status").json().path("drainMode").asBoolean())
                .isFalse();
        assertThat(client().get("/api/status").json().path("version").asString())
                .as("from Maven build-info")
                .matches("\\d+\\.\\d+\\.\\d+.*");
        jdbc.sql("UPDATE app_setting SET value = 'true'::jsonb WHERE key = 'drain_mode'")
                .update();
        settings.refresh();
        assertThat(client().get("/api/status").json().path("drainMode").asBoolean())
                .isTrue();
        assertThat(client().get("/api/status").json().path("freeGamesEnabled").asBoolean())
                .isTrue();
    }
}
