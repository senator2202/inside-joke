package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.game.dto.AssignmentDto;
import com.insidejoke.game.dto.OptionDto;
import com.insidejoke.game.dto.RoomStateDto;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What each role is sent (blueprint 4.5): the dossier reaches no one, answers stay anonymous and the truth stays
 * hidden until the reveal, prompts reach only their writers, and a hidden room code reaches only the owner's screen.
 */
class RoomProjectionServiceTest {

    private final GameHarness h = new GameHarness();

    @Test
    void secretsReachNobodyOnlyTheirCount() {
        GameHarness.Party party = h.party(3);
        h.screenCopy(party.room());
        h.send(
                        party,
                        party.seat(0),
                        "dossier.add",
                        Map.of("aboutPlayerId", party.seat(1).id(), "text", "Owns forty cacti"))
                .ok();
        assertThat(h.allViews(party.room())).doesNotContain("forty cacti");
        h.toAnswering(party);
        assertThat(h.allViews(party.room())).doesNotContain("forty cacti");
        assertThat(h.view(party.room(), party.owner()).secrets()).isEqualTo(1);
    }

    @Test
    void eachPlayerSeesOnlyTheirOwnPromptsWhileWriting() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        for (GameHarness.Seat seat : party.seats()) {
            assertThat(h.view(party, seat).you().assignments())
                    .extracting(AssignmentDto::duelId)
                    .containsExactlyInAnyOrderElementsOf(h.duelsOf(party, seat).stream()
                            .map(DuelState::getId)
                            .toList());
        }
        RoomStateDto screen = h.view(party.room(), party.owner());
        assertThat(screen.round().prompt()).as("the screen keeps the surprise").isNull();
        assertThat(screen.round().progress()).hasSize(3).allMatch(p -> p.total() == 2 && p.answered() == 0);
        assertThat(screen.you().assignments()).isNull();
    }

    @Test
    void answersStayAnonymousUntilTheReveal() {
        GameHarness.Party party = h.party(4);
        h.toAnswering(party);
        h.answerAll(party);
        DuelState duel = h.read(party.room(), (RoomState r) -> r.getRound().currentDuel());

        for (OptionDto option : h.view(party.room(), party.owner()).round().options()) {
            assertThat(option.authorId()).isNull();
            assertThat(option.votes()).isNull();
            assertThat(option.points()).isNull();
        }
        assertThat(h.view(party.room(), party.owner()).round().options())
                .extracting(OptionDto::text)
                .containsExactly(duel.getAnswerA(), duel.getAnswerB());

        h.voters(party)
                .forEach(v ->
                        h.send(party, v, "vote.submit", Map.of("optionId", "A")).ok());
        OptionDto a = h.view(party.room(), party.owner()).round().options().getFirst();
        assertThat(a.authorId()).isEqualTo(duel.getPlayerA());
        assertThat(a.votes()).isEqualTo(2);
        assertThat(a.points()).isEqualTo(450);
        assertThat(a.winner()).isTrue();
    }

    @Test
    void theTruthIsToldOnlyAtTheReveal() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        h.skipTo(party, Phase.ROUND_VOTE);
        party.seats().forEach(s -> h.send(party, s, "round.kind.vote", Map.of("kind", "TRUTH_OR_AI")));
        h.owner(party, "game.next");

        RoomStateDto voting = h.view(party.room(), party.owner());
        assertThat(voting.round().statement()).isNotBlank();
        assertThat(voting.round().options()).allMatch(o -> o.correct() == null && o.votes() == null);

        h.owner(party, "game.next");
        RoomStateDto reveal = h.view(party.room(), party.owner());
        String right = h.round(party).isStatementTrue() ? "truth" : "ai";
        assertThat(reveal.round().options()).allMatch(o -> o.correct() == o.id().equals(right));
    }

    @Test
    void aHiddenCodeReachesOnlyTheOwnersScreen() {
        GameHarness.Party party =
                h.party(3, new RoomSettings(Tone.FAMILY, GameLength.SHORT, RoomMode.STREAMER, true, null));
        Member copy = h.screenCopy(party.room());
        Member viewer = h.viewer(party.room());
        String code = party.room().getCode();

        RoomStateDto owner = h.view(party.room(), party.owner());
        assertThat(owner.code()).isEqualTo(code);
        assertThat(owner.lobby().joinUrl()).isEqualTo("http://localhost/j/" + code);
        assertThat(owner.lobby().audienceUrl())
                .isEqualTo("http://localhost/w/" + party.room().getAudienceKey())
                .doesNotContain(code);

        RoomStateDto onCopy = h.view(party.room(), copy);
        assertThat(onCopy.code()).isNull();
        assertThat(onCopy.lobby().joinUrl()).isNull();

        RoomStateDto onStream = h.view(party.room(), viewer);
        assertThat(onStream.code()).isNull();
        assertThat(onStream.players()).isNull();
        assertThat(onStream.secrets()).isNull();
        assertThat(onStream.lobby()).isNull();
        assertThat(h.allViews(party.room()).split("\n"))
                .as("the code reaches only the owner's screen and the players' phones")
                .filteredOn(v -> v.contains("\"role\":\"SCREEN\"") || v.contains("\"role\":\"AUDIENCE\""))
                .noneMatch(v -> v.contains(code));
    }

    @Test
    void theDeadlineIsShownOnlyWhileTheClockRuns() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        assertThat(h.view(party.room(), party.owner()).deadline()).isEqualTo(h.clock.millis() + 60_000);
        h.owner(party, "game.pause");
        assertThat(h.view(party, party.seat(0)).deadline()).isNull();
    }
}
