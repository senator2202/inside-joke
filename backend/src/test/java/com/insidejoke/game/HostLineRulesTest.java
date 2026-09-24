package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.insidejoke.common.ErrorCode;
import com.insidejoke.game.dto.HostDto;
import com.insidejoke.moderation.ModerationAction;
import com.insidejoke.moderation.ModerationStage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The host's lines (user flow S6, P8; blueprint 5.3): voiced on the screens only, and a line can be skipped by the
 * player it is about or by the owner, never by anyone else.
 */
class HostLineRulesTest {

    private final GameHarness h = new GameHarness();

    /** Round 1's first duel is revealed; the host's line is about the two duellists. */
    private GameHarness.Party atReveal() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        h.answerAll(party);
        h.voters(party)
                .forEach(v ->
                        h.send(party, v, "vote.submit", Map.of("optionId", "A")).ok());
        assertThat(h.phase(party)).isEqualTo(Phase.REVEAL);
        return party;
    }

    private HostLine line(GameHarness.Party party) {
        return h.read(party.room(), RoomState::getHostLine);
    }

    @Test
    void theVoiceIsPlayedOnTheScreensOnly() {
        GameHarness.Party party = h.party(3);
        Member copy = h.screenCopy(party.room());
        assertThat(line(party).audioId()).isNotNull();
        assertThat(h.view(party.room(), party.owner()).host().audioId())
                .isEqualTo(line(party).audioId());
        assertThat(h.view(party.room(), copy).host().audioId())
                .isEqualTo(line(party).audioId());
        assertThat(h.view(party, party.seat(0)).host().audioId()).isNull();
    }

    @Test
    void withoutTheVoiceTheLineIsJustText() {
        h.ai.down = true;
        GameHarness.Party party = h.party(3);
        assertThat(line(party).text()).isNotBlank();
        assertThat(line(party).audioId()).isNull();
    }

    @Test
    void aPlayerMaySkipALineAboutThemselvesOnly() {
        GameHarness.Party party = atReveal();
        HostLine about = line(party);
        List<GameHarness.Seat> named = party.seats().stream()
                .filter(s -> about.aboutPlayerIds().contains(s.id()))
                .toList();
        GameHarness.Seat bystander = party.seats().stream()
                .filter(s -> !named.contains(s))
                .findFirst()
                .orElseThrow();
        assertThat(h.view(party, bystander).host().canSkip()).isFalse();
        assertThat(h.send(party, bystander, "line.skip", Map.of("lineId", about.id()))
                        .error())
                .isEqualTo(ErrorCode.NOT_ALLOWED);

        GameHarness.Seat subject = named.getFirst();
        assertThat(h.view(party, subject).host().canSkip()).isTrue();
        h.send(party, subject, "line.skip", Map.of("lineId", about.id())).ok();

        HostDto skipped = h.view(party.room(), party.owner()).host();
        assertThat(skipped.skipped()).isTrue();
        assertThat(skipped.text()).isEqualTo("This line was skipped at a player's request.");
        assertThat(skipped.audioId()).isNull();
        verify(h.moderation)
                .insert(
                        any(),
                        eq(ModerationStage.AI_OUTPUT),
                        eq("host_line"),
                        eq(ModerationAction.SKIPPED_BY_PLAYER),
                        any());
        assertThat(h.send(party, subject, "line.skip", Map.of("lineId", about.id()))
                        .error())
                .isEqualTo(ErrorCode.INVALID_PHASE);
    }

    @Test
    void theOwnerMaySkipAnyLine() {
        GameHarness.Party party = h.party(3);
        h.owner(party, "line.skip", Map.of("lineId", line(party).id()));
        assertThat(line(party).skipped()).isTrue();
        verify(h.moderation)
                .insert(
                        any(),
                        eq(ModerationStage.AI_OUTPUT),
                        eq("host_line"),
                        eq(ModerationAction.SKIPPED_BY_OWNER),
                        any());
    }

    @Test
    void onlyTheCurrentLineCanBeSkipped() {
        GameHarness.Party party = h.party(3);
        String old = line(party).id();
        h.join(party, "Dan");
        assertThat(line(party).id()).as("the host greets the newcomer").isNotEqualTo(old);
        assertThat(h.send(party.room(), party.owner(), "line.skip", Map.of("lineId", old))
                        .error())
                .isEqualTo(ErrorCode.INVALID_PHASE);
    }

    @Test
    void aSkippedLineGetsNoVoiceWhenItArrivesLate() {
        GameHarness.Party party = atReveal();
        h.hold();
        h.owner(party, "game.next");
        HostLine next = line(party);
        h.owner(party, "line.skip", Map.of("lineId", next.id()));
        h.release();
        assertThat(line(party).skipped()).isTrue();
        assertThat(line(party).audioId()).isNull();
    }
}
