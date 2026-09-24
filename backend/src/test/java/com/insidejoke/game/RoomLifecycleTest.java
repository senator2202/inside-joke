package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.insidejoke.common.ErrorCode;
import com.insidejoke.game.dto.LiveStatsDto;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A room's life (blueprint 4.6): closing after 30 idle minutes or 4 hours, the owner closing it, nothing of the
 * players' content surviving the room, and the running totals for the admin.
 */
class RoomLifecycleTest {

    private final GameHarness h = new GameHarness();

    @Test
    void anIdleRoomClosesAfterThirtyMinutesAndIsForgottenSoonAfter() {
        GameHarness.Party party = h.party(3);
        h.advance(Duration.ofMinutes(29));
        h.sweep();
        assertThat(h.phase(party)).isEqualTo(Phase.LOBBY);

        h.advance(Duration.ofMinutes(2));
        h.sweep();
        assertThat(h.phase(party)).isEqualTo(Phase.CLOSED);
        assertThat(h.events.closed).containsExactly(party.room().getCode());
        assertThat(h.engine.find(party.room().getCode())).isEmpty();
        assertThat(h.engine.resolve(party.seat(0).token())).isEmpty();
        assertThat(h.registry.find(party.room().getCode()))
                .as("kept a moment for the goodbye")
                .isPresent();

        h.advance(Duration.ofMinutes(3));
        h.sweep();
        assertThat(h.registry.find(party.room().getCode())).isEmpty();
        assertThat(h.registry.byToken(party.seat(0).token())).isEmpty();
    }

    @Test
    void aGameLeftAloneEndsItsSessionAsIdle() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        h.owner(party, "game.pause");
        h.advance(Duration.ofMinutes(31));
        h.sweep();
        assertThat(h.phase(party)).isEqualTo(Phase.CLOSED);
        verify(h.sessions).finish(any(), argThat(f -> f.reason() == EndReason.IDLE && f.playerCount() == 3));
    }

    @Test
    void evenABusyRoomClosesAfterFourHours() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        h.owner(party, "game.pause");
        for (int i = 0; i < 13 && h.phase(party) != Phase.CLOSED; i++) {
            h.advance(Duration.ofMinutes(20));
            h.owner(party, "room.lock", Map.of("locked", i % 2 == 0));
            h.sweep();
        }
        assertThat(h.phase(party)).isEqualTo(Phase.CLOSED);
        verify(h.sessions).finish(any(), argThat(f -> f.reason() == EndReason.MAX_AGE));
    }

    @Test
    void theOwnerClosesTheRoomAndNothingOfThePlayersContentStays() {
        GameHarness.Party party = h.party(3);
        h.send(party, party.seat(0), "dossier.add", Map.of("text", "Owns forty cacti"))
                .ok();
        h.toAnswering(party);
        h.owner(party, "room.close");

        assertThat(h.phase(party)).isEqualTo(Phase.CLOSED);
        assertThat(h.events.closed).containsExactly(party.room().getCode());
        assertThat(h.read(party.room(), (RoomState r) -> r.getDossier().isEmpty()))
                .isTrue();
        assertThat(h.player(party, party.seat(0)).getIntake()).containsOnlyNulls();
        verify(h.sessions).finish(any(), argThat(f -> f.reason() == EndReason.ENDED_BY_HOST && f.dossierFacts() == 1));

        assertThat(h.send(party, party.seat(0), "dossier.add", Map.of("text", "Too late"))
                        .error())
                .isEqualTo(ErrorCode.ROOM_NOT_FOUND);
        assertThat(h.joinError(party.room(), "Dan")).isEqualTo(ErrorCode.ROOM_NOT_FOUND);
    }

    @Test
    void liveStatsCountOpenRoomsGamesPlayersAndViewers() {
        GameHarness.Party lobby = h.party(3);
        GameHarness.Party stream =
                h.party(3, new RoomSettings(Tone.FAMILY, GameLength.SHORT, RoomMode.STREAMER, false, null));
        h.viewer(stream.room());
        h.toAnswering(stream);
        GameHarness.Party closed = h.party(3);
        h.owner(closed, "room.close");

        assertThat(h.engine.liveStats()).isEqualTo(new LiveStatsDto(2, 1, 6, 1));
        assertThat(h.phase(lobby)).isEqualTo(Phase.LOBBY);
    }

    @Test
    void aServerShutdownRecordsTheGamesInProgress() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        h.engine.shutdown();
        verify(h.sessions).finish(any(), argThat(f -> f.reason() == EndReason.SHUTDOWN));
    }

    @Test
    void theQuickRatingIsOneToThree() {
        GameHarness.Party party = h.party(3);
        assertThat(h.send(party, party.seat(0), "feedback.quick", Map.of("score", 4))
                        .error())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        h.send(party, party.seat(0), "feedback.quick", Map.of("score", 3)).ok();
        verify(h.analytics).track(eq("feedback_submitted"), anyString(), eq(Map.of("source", "player", "score", 3)));
    }

    @Test
    void everyChangeGetsANewVersionAndIsBroadcast() {
        GameHarness.Party party = h.party(3);
        long version = h.read(party.room(), RoomState::getVersion);
        int changes = h.events.changes;
        h.owner(party, "room.lock", Map.of("locked", true));
        assertThat(h.read(party.room(), RoomState::getVersion)).isGreaterThan(version);
        assertThat(h.events.changes).isGreaterThan(changes);
        assertThat(h.view(party.room(), party.owner()).locked()).isTrue();
        assertThat(h.view(party, party.seat(0)).version()).isEqualTo(h.read(party.room(), RoomState::getVersion));
    }

    @Test
    void backgroundWorkForAClosedRoomIsDropped() {
        GameHarness.Party party = h.party(3);
        h.hold();
        h.start(party);
        h.owner(party, "room.close");
        h.release();
        assertThat(h.access.starts).hasSize(1);
        assertThat(h.phase(party)).isEqualTo(Phase.CLOSED);
    }

    @Test
    void aDeletedAccountsRoomsClose() {
        GameHarness.Party lobby = h.party(3);
        GameHarness.Party game = h.party(3);
        h.toAnswering(game);
        h.engine.closeRoomsOf(GameHarness.HOST);
        h.settle();
        assertThat(h.phase(lobby)).isEqualTo(Phase.CLOSED);
        assertThat(h.phase(game)).isEqualTo(Phase.CLOSED);
        verify(h.sessions).finish(any(), argThat(f -> f.reason() == EndReason.ENDED_BY_HOST));
    }
}
