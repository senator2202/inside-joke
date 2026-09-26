package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.common.ErrorCode;
import com.insidejoke.game.dto.PlayerDto;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Test bots (roadmap R34): an admin's room lets its owner add bots that play by the players' rules on their own. */
class BotPlayersTest {

    private final GameHarness h = new GameHarness();

    private List<PlayerState> bots(GameHarness.Party party) {
        return h.read(
                party.room(),
                (RoomState r) ->
                        r.activePlayers().stream().filter(PlayerState::isBot).toList());
    }

    private void addBots(GameHarness.Party party, int count) {
        for (int i = 0; i < count; i++) {
            h.owner(party, "bot.add");
        }
    }

    /** The one person plays their part of whatever is going on; the bots are left to themselves. */
    private void personPlays(GameHarness.Party party, GameHarness.Seat person) {
        switch (h.phase(party)) {
            case INTAKE -> {
                if (h.player(party, person).getIntakeGame() == 0) {
                    h.send(party, person, "intake.submit", Map.of("answers", List.of("Pizza", "Karaoke", "Shoes")))
                            .ok();
                }
            }
            case ROUND_VOTE -> {
                if (!h.read(party.room(), (RoomState r) -> r.getKindVotes().containsKey(person.id()))) {
                    h.send(party, person, "round.kind.vote", Map.of("kind", "ANSWER_DUEL"))
                            .ok();
                }
            }
            case ANSWERING -> {
                for (DuelState d : h.duelsOf(party, person)) {
                    if (d.answerOf(person.id()) == null) {
                        h.send(party, person, "answer.submit", Map.of("duelId", d.getId(), "text", "Mine"))
                                .ok();
                    }
                }
            }
            case VOTING -> {
                VoteStepState step =
                        h.read(party.room(), (RoomState r) -> r.getRound().currentVote());
                if (step != null
                        && step.getEligible().contains(person.id())
                        && !step.getVotes().containsKey(person.id())) {
                    h.send(
                                    party,
                                    person,
                                    "vote.submit",
                                    Map.of("optionId", step.getOptions().getFirst()))
                            .ok();
                }
            }
            default -> {
                // Nothing to do.
            }
        }
    }

    @Test
    void onePersonPlaysAWholeGameWithBots() {
        GameHarness.Party party = h.adminParty();
        addBots(party, 1);
        GameHarness.Seat person = h.join(party, "Ann");
        addBots(party, 2);
        assertThat(party.captain()).as("a person leads, not a bot").isEqualTo(person);

        h.start(party);
        for (int i = 0; i < 1_200 && h.phase(party) != Phase.FINALE; i++) {
            personPlays(party, person);
            h.advance(Duration.ofSeconds(1));
        }

        assertThat(h.phase(party)).isEqualTo(Phase.FINALE);
        assertThat(bots(party)).hasSize(3).allSatisfy(bot -> {
            assertThat(bot.getIntakeGame())
                    .as(bot.getName() + " answered the questions")
                    .isPositive();
            assertThat(bot.getAnswersGiven())
                    .as(bot.getName() + " wrote answers")
                    .isPositive();
        });
    }

    @Test
    void aBotTakesItsTimeLikeAPerson() {
        GameHarness.Party party = h.adminParty();
        GameHarness.Seat person = h.join(party, "Ann");
        addBots(party, 2);
        h.start(party);
        personPlays(party, person);

        h.advance(Duration.ofMillis(BotPlayerHandler.MIN_DELAY_MS - 1));
        assertThat(bots(party)).allSatisfy(b -> assertThat(b.getIntakeGame()).isZero());
        h.advance(Duration.ofMillis(BotPlayerHandler.MAX_DELAY_MS));
        assertThat(bots(party)).allSatisfy(b -> assertThat(b.getIntakeGame()).isPositive());
    }

    @Test
    void withoutAModelABotSkipsTheQuestionsInsteadOfHoldingTheGameUp() {
        GameHarness.Party party = h.adminParty();
        GameHarness.Seat person = h.join(party, "Ann");
        addBots(party, 2);
        h.start(party);
        personPlays(party, person);
        h.ai.down = true;

        h.advance(Duration.ofMillis(3 * BotPlayerHandler.MAX_DELAY_MS));

        assertThat(bots(party)).allSatisfy(b -> {
            assertThat(b.getIntakeGame()).isPositive();
            assertThat(b.intakeAnswered()).isZero();
        });
    }

    @Test
    void botsWaitWhileTheGameIsPaused() {
        GameHarness.Party party = h.adminParty();
        GameHarness.Seat person = h.join(party, "Ann");
        addBots(party, 2);
        h.start(party);
        h.owner(party, "game.pause");

        h.advance(Duration.ofMillis(3 * BotPlayerHandler.MAX_DELAY_MS));
        assertThat(bots(party)).allSatisfy(b -> assertThat(b.getIntakeGame()).isZero());

        h.owner(party, "game.resume");
        personPlays(party, person);
        h.advance(Duration.ofMillis(BotPlayerHandler.MAX_DELAY_MS));
        assertThat(bots(party)).allSatisfy(b -> assertThat(b.getIntakeGame()).isPositive());
    }

    @Test
    void onlyAnAdminsRoomTakesBots() {
        GameHarness.Party ordinary = h.party(1);
        assertThat(h.send(ordinary.room(), ordinary.owner(), "bot.add").error()).isEqualTo(ErrorCode.NOT_ALLOWED);
        assertThat(h.view(ordinary.room(), ordinary.owner()).lobby().botsAllowed())
                .isNull();

        GameHarness.Party admin = h.adminParty();
        assertThat(h.view(admin.room(), admin.owner()).lobby().botsAllowed()).isTrue();
        GameHarness.Seat person = h.join(admin, "Ann");
        assertThat(h.send(admin, person, "bot.add").error()).isEqualTo(ErrorCode.NOT_ALLOWED);
    }

    @Test
    void botsAreMarkedFillOnlyFreeSeatsAndNeverLead() {
        GameHarness.Party party = h.adminParty();
        GameHarness.Seat person = h.join(party, "Ann");
        addBots(party, h.props.maxPlayers() - 1);
        assertThat(h.send(party.room(), party.owner(), "bot.add").error()).isEqualTo(ErrorCode.ROOM_FULL);

        List<PlayerDto> players = h.view(party, person).players();
        assertThat(players).filteredOn(p -> Boolean.TRUE.equals(p.bot())).hasSize(h.props.maxPlayers() - 1);
        assertThat(players)
                .filteredOn(p -> p.id().equals(person.id()))
                .singleElement()
                .satisfies(p -> assertThat(p.bot()).isNull());
        assertThat(players).extracting(PlayerDto::name).doesNotHaveDuplicates();

        h.owner(party, "player.kick", Map.of("playerId", person.id()));
        assertThat(h.read(party.room(), RoomState::getCaptainId))
                .as("no bot takes over")
                .isNull();
    }

    @Test
    void aBotCanNotBeAddedOnceTheGameIsUnderWay() {
        GameHarness.Party party = h.adminParty();
        GameHarness.Seat person = h.join(party, "Ann");
        addBots(party, 2);
        h.start(party);
        personPlays(party, person);
        h.advance(Duration.ofMillis(BotPlayerHandler.MAX_DELAY_MS));
        assertThat(h.phase(party)).isNotIn(Phase.LOBBY, Phase.INTAKE);

        assertThat(h.send(party.room(), party.owner(), "bot.add").error()).isEqualTo(ErrorCode.ROOM_IN_PROGRESS);
    }
}
