package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.moderation.ModerationAction;
import com.insidejoke.moderation.ModerationStage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The intake (three questions, 90 s) and the secrets players give the host (user flow S2, P3, P4; blueprint 10.2). */
class IntakeRulesTest {

    private static final List<String> GOOD = List.of("Pizza", "Karaoke", "Lost a shoe");

    private final GameHarness h = new GameHarness();

    private GameHarness.Answer intake(GameHarness.Party party, GameHarness.Seat seat, List<String> answers) {
        return h.send(party, seat, "intake.submit", Map.of("answers", answers));
    }

    private GameHarness.Answer secret(GameHarness.Party party, GameHarness.Seat seat, Map<String, ?> data) {
        return h.send(party, seat, "dossier.add", data);
    }

    @Test
    void theIntakeLastsNinetySecondsAndEndsOnceEveryoneHasAnswered() {
        GameHarness.Party party = h.party(3);
        h.start(party);
        assertThat(h.read(party.room(), RoomState::getDeadlineMs)).isEqualTo(h.clock.millis() + 90_000);
        assertThat(h.view(party, party.seat(0)).intake().questions()).hasSize(3);

        intake(party, party.seat(0), GOOD).ok();
        intake(party, party.seat(1), GOOD).ok();
        assertThat(h.phase(party)).isEqualTo(Phase.INTAKE);
        assertThat(h.view(party.room(), party.owner()).intake().done()).isEqualTo(2);

        intake(party, party.seat(2), GOOD).ok();
        assertThat(h.phase(party)).isEqualTo(Phase.ANSWERING);
        assertThat(h.round(party).getKind())
                .as("the first round is always an answer duel")
                .isEqualTo(RoundKind.ANSWER_DUEL);
        assertThat(h.player(party, party.seat(0)).getIntake()).containsExactly("Pizza", "Karaoke", "Lost a shoe");
    }

    @Test
    void whenTimeRunsOutTheGameGoesOnWithoutTheLatecomers() {
        GameHarness.Party party = h.party(3);
        h.start(party);
        intake(party, party.seat(0), GOOD).ok();

        h.advance(Duration.ofSeconds(61));
        assertThat(h.phase(party)).isEqualTo(Phase.INTAKE);
        assertThat(h.ai.roundRequests)
                .as("round 1 is prepared a third before the end")
                .hasSize(1);

        h.advance(Duration.ofSeconds(30));
        assertThat(h.phase(party)).isEqualTo(Phase.ANSWERING);
        assertThat(h.player(party, party.seat(1)).getIntake()).containsOnlyNulls();
    }

    @Test
    void theOwnerOrCaptainNeedNotWait() {
        GameHarness.Party party = h.party(3);
        h.start(party);
        h.send(party, party.captain(), "game.next").ok();
        assertThat(h.phase(party)).isEqualTo(Phase.ANSWERING);
    }

    @Test
    void answersAreCheckedByRulesThenByTheModelQuestionByQuestion() {
        GameHarness.Party party = h.party(3);
        h.start(party);
        GameHarness.Seat p = party.seat(0);

        ApiException byRules = intake(party, p, List.of("Pizza", "Visit www.example.com", "Karaoke"))
                .exception();
        assertThat(byRules.code()).isEqualTo(ErrorCode.MODERATION_BLOCKED);
        assertThat(byRules.details()).containsEntry("rejected", List.of(1));

        ApiException byModel =
                intake(party, p, List.of("Pizza", "BANNED", "Karaoke")).exception();
        assertThat(byModel.code()).isEqualTo(ErrorCode.MODERATION_BLOCKED);
        assertThat(byModel.details()).containsEntry("rejected", List.of(1));
        verify(h.moderation)
                .insert(any(), eq(ModerationStage.INTAKE), eq("harmful"), eq(ModerationAction.BLOCKED), any());

        assertThat(intake(party, p, List.of("a", "b")).error()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(intake(party, p, List.of("x".repeat(121), "b", "c")).error()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(h.player(party, p).getIntakeGame()).isZero();

        intake(party, p, List.of("Pizza", "Dancing", "Karaoke")).ok();
        assertThat(h.player(party, p).getIntakeGame()).isEqualTo(1);
        assertThat(h.view(party, p).you().intakeNeeded()).isFalse();
    }

    @Test
    void blankAnswersAreKeptEmptyWithoutAskingTheModel() {
        GameHarness.Party party = h.party(3);
        h.start(party);
        intake(party, party.seat(0), List.of("", " ", "")).ok();
        assertThat(h.ai.moderated).isEmpty();
        assertThat(h.player(party, party.seat(0)).getIntake()).containsOnlyNulls();
    }

    @Test
    void withTheModelDownTheIntakeCannotBeChecked() {
        GameHarness.Party party = h.party(3);
        h.start(party);
        h.ai.down = true;
        assertThat(intake(party, party.seat(0), GOOD).error()).isEqualTo(ErrorCode.MODERATION_UNAVAILABLE);
        assertThat(h.player(party, party.seat(0)).getIntakeGame()).isZero();
    }

    @Test
    void secretsAreAboutOneselfOrAnotherPlayerAndTenPerGame() {
        GameHarness.Party party = h.party(3);
        GameHarness.Seat p = party.seat(0);
        GameHarness.Seat friend = party.seat(1);

        assertThat(secret(party, p, Map.of("aboutPlayerId", friend.id(), "text", "Sings in the shower"))
                        .ok())
                .containsEntry("secretsLeft", 9);
        for (int i = 0; i < 9; i++) {
            secret(party, p, Map.of("text", "Harmless story number " + i)).ok();
        }
        assertThat(secret(party, p, Map.of("text", "One more")).error()).isEqualTo(ErrorCode.DOSSIER_LIMIT);

        List<DossierFact> dossier = h.read(party.room(), (RoomState r) -> List.copyOf(r.getDossier()));
        assertThat(dossier).hasSize(10);
        assertThat(dossier.getFirst().aboutPlayerId()).isEqualTo(friend.id());
        assertThat(dossier.get(1).aboutPlayerId())
                .as("about oneself by default")
                .isEqualTo(p.id());
        assertThat(h.view(party, p).you().secretsLeft()).isZero();
        assertThat(h.view(party.room(), party.owner()).secrets()).isEqualTo(10);
    }

    @Test
    void secretsOnForbiddenTopicsAreRefusedByRulesAndByTheModel() {
        GameHarness.Party party = h.party(3);
        GameHarness.Seat p = party.seat(0);
        assertThat(secret(party, p, Map.of("text", "Call me on +44 20 7946 0958"))
                        .error())
                .isEqualTo(ErrorCode.MODERATION_BLOCKED);
        assertThat(secret(party, p, Map.of("text", "She was diagnosed with something"))
                        .error())
                .isEqualTo(ErrorCode.MODERATION_BLOCKED);
        assertThat(h.ai.moderated).as("the rules alone refused those").isEmpty();

        assertThat(secret(party, p, Map.of("text", "BANNED story about work")).error())
                .isEqualTo(ErrorCode.MODERATION_BLOCKED);
        assertThat(secret(party, p, Map.of("aboutPlayerId", "nobody", "text", "x"))
                        .error())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(secret(party, p, Map.of("text", "x".repeat(201))).error()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(secret(party, p, Map.of("text", "  ")).error()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(h.read(party.room(), (RoomState r) -> r.getDossier().size())).isZero();
        assertThat(h.view(party, p).you().secretsLeft())
                .as("refused secrets don't count")
                .isEqualTo(10);
        verify(h.moderation)
                .insert(any(), eq(ModerationStage.DOSSIER), eq("harmful"), eq(ModerationAction.BLOCKED), any());
    }

    @Test
    void secretsAreRefusedWhenTheyCannotBeChecked() {
        GameHarness.Party party = h.party(3);
        h.ai.down = true;
        assertThat(secret(party, party.seat(0), Map.of("text", "A harmless story"))
                        .error())
                .isEqualTo(ErrorCode.MODERATION_UNAVAILABLE);
        assertThat(h.view(party, party.seat(0)).you().secretsLeft()).isEqualTo(10);

        h.ai.down = false;
        h.ai.secretsCheckable = false;
        assertThat(secret(party, party.seat(0), Map.of("text", "A harmless story"))
                        .error())
                .isEqualTo(ErrorCode.MODERATION_UNAVAILABLE);
        assertThat(h.read(party.room(), (RoomState r) -> r.getDossier().size())).isZero();
    }

    @Test
    void onePlayerCannotBurnEveryonesSecretChecks() {
        GameHarness.Party party = h.party(3);
        GameHarness.Seat spammer = party.seat(0);
        for (int i = 0; i < h.props.maxModerationChecksPerPlayer(); i++) {
            assertThat(secret(party, spammer, Map.of("text", "BANNED nonsense " + i))
                            .error())
                    .isEqualTo(ErrorCode.MODERATION_BLOCKED);
        }
        assertThat(secret(party, spammer, Map.of("text", "A harmless story")).error())
                .isEqualTo(ErrorCode.DOSSIER_LIMIT);
        secret(party, party.seat(1), Map.of("text", "Another harmless story")).ok();
    }

    @Test
    void secretsGivenDuringTheGameReachLaterRounds() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        secret(party, party.seat(0), Map.of("aboutPlayerId", party.seat(1).id(), "text", "Owns forty cacti"))
                .ok();

        h.skipTo(party, Phase.ROUND_VOTE);
        h.skipTo(party, Phase.VOTING);
        h.skipTo(party, Phase.ROUND_VOTE);
        HostAiService.RoundParams later = h.ai.roundRequests.getLast();
        assertThat(later.roundNumber()).isGreaterThan(2);
        assertThat(later.dossier()).extracting(HostAiService.Fact::text).contains("Owns forty cacti");
    }
}
