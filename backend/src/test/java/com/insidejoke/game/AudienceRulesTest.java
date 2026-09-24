package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.game.dto.RoomStateDto;
import com.insidejoke.settings.AppSettingsService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Stream viewers (user flow A1-A4): only in streamer mode, up to the cap, one vote a step for the audience bonus. */
class AudienceRulesTest {

    private static final RoomSettings STREAMER =
            new RoomSettings(Tone.CHEEKY, GameLength.SHORT, RoomMode.STREAMER, true, null);

    private final GameHarness h = new GameHarness();

    @Test
    void viewersJoinOnlyStreamerRoomsAndUpToTheCap() {
        GameHarness.Party standard = h.party(3);
        assertThatThrownBy(() -> h.engine.joinAudience(standard.room()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_STREAMER_MODE));

        h.snapshot = new AppSettingsService.Snapshot(true, true, 5_000_000, 1, false);
        GameHarness.Party stream = h.party(3, STREAMER);
        Member viewer = h.viewer(stream.room());
        assertThatThrownBy(() -> h.engine.joinAudience(stream.room()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.AUDIENCE_FULL));

        assertThat(h.view(stream.room(), stream.owner()).lobby().audienceCount())
                .isEqualTo(1);
        h.disconnect(stream.room(), viewer);
        assertThat(h.read(stream.room(), RoomState::getAudienceCount)).isZero();
        assertThat(h.read(stream.room(), RoomState::getAudiencePeak)).isEqualTo(1);
    }

    @Test
    void theAudienceVotesOnceAStepAndItsMajorityIsWorthAHundred() {
        GameHarness.Party party = h.party(3, STREAMER);
        Member viewer = h.viewer(party.room());
        assertThat(h.send(party.room(), viewer, "audience.vote", Map.of("optionId", "A"))
                        .error())
                .as("nothing to vote on in the lobby")
                .isEqualTo(ErrorCode.INVALID_PHASE);
        h.toAnswering(party);
        h.answerAll(party);
        DuelState duel = h.read(party.room(), (RoomState r) -> r.getRound().currentDuel());

        assertThat(h.send(party.room(), viewer, "vote.submit", Map.of("optionId", "A"))
                        .error())
                .isEqualTo(ErrorCode.NOT_ALLOWED);
        assertThat(h.send(party.room(), viewer, "audience.vote", Map.of("optionId", "C"))
                        .error())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        h.send(party.room(), viewer, "audience.vote", Map.of("optionId", "A")).ok();
        h.send(party.room(), viewer, "audience.vote", Map.of("optionId", "B")).ok();

        RoomStateDto onScreen = h.view(party.room(), party.owner());
        assertThat(onScreen.round().audienceTotal()).isEqualTo(1);
        assertThat(onScreen.round().options().getFirst().audienceVotes()).isEqualTo(1);
        assertThat(h.view(party.room(), viewer).audience().voted()).isTrue();

        List<GameHarness.Seat> voters = h.voters(party);
        h.send(party, voters.getFirst(), "vote.submit", Map.of("optionId", "B")).ok();
        assertThat(h.phase(party)).isEqualTo(Phase.REVEAL);
        assertThat(duel.getPointsA()).as("the audience's pick").isEqualTo(100);
        assertThat(duel.getPointsB()).as("one player's vote").isEqualTo(100);
    }
}
