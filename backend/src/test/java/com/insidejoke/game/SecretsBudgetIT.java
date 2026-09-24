package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.Await;
import com.insidejoke.support.FakeAi;
import com.insidejoke.support.Party;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecretsBudgetIT extends AbstractIntegrationTest {

    @Test
    void aLivelyPartyCanShareSecretsWithoutStarvingTheHost() {
        FakeAi.install(FAKE);
        try (Party party = Party.create(port, json, FAKE, 5)) {
            for (Party.Phone p : party.phones) {
                for (int i = 0; i < 9; i++) {
                    p.socket().ok("dossier.add", Map.of("text", "A harmless story number " + i + " about " + p.name()));
                }
            }
            assertThat(party.screen.state(s -> s.path("secrets").asInt() == 45)).isNotNull();

            party.captain().socket().ok("game.start", Map.of());
            party.screen.phase("INTAKE");
            party.screen.ok("game.next", Map.of());
            party.screen.phase("ANSWERING");
            String session = jdbc.sql("SELECT id::text FROM game_session WHERE room_code = ?")
                    .param(party.code)
                    .query(String.class)
                    .single();
            Await.until(
                    "round written by the AI",
                    () -> jdbc.sql("SELECT count(*) FROM ai_call WHERE game_session_id = ?::uuid "
                                            + "AND purpose = 'ROUND_GEN' AND outcome = 'OK'")
                                    .param(session)
                                    .query(Integer.class)
                                    .single()
                            > 0);
        }
    }

    @Test
    void onePlayerCannotBurnEveryonesSecretChecks() {
        FakeAi.install(FAKE);
        try (Party party = Party.create(port, json, FAKE, 3)) {
            Party.Phone spammer = party.phones.get(0);
            for (int i = 0; i < 25; i++) {
                assertThat(spammer.socket().error("dossier.add", Map.of("text", "BANNED nonsense " + i)))
                        .isEqualTo("MODERATION_BLOCKED");
            }
            assertThat(spammer.socket().error("dossier.add", Map.of("text", "A harmless story")))
                    .isEqualTo("DOSSIER_LIMIT");
            party.phones.get(1).socket().ok("dossier.add", Map.of("text", "Another harmless story"));
        }
    }
}
