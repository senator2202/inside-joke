package com.insidejoke.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Plays for the test bots of a room (roadmap R34). After every change it looks at what each bot still owes the game
 * (intake answers, a round-kind vote, duel answers, a vote) and plans that move once, a few seconds ahead, like a person
 * thinking. The engine sends each move as the bot's own command, so bots follow exactly the rules players do. Bots
 * don't write secrets: those need a model check, and a test game plays without them. Runs under the room lock.
 */
final class BotPlayerHandler {

    /** One command a bot will send after {@code delayMs}, for the game step {@code step} (planned once). */
    record Move(String playerId, String step, CommandType command, ObjectNode data, long delayMs) {}

    static final long MIN_DELAY_MS = 1_500;
    static final long MAX_DELAY_MS = 5_000;

    private final FallbackContentService fallback;
    private final RandomGenerator random;

    BotPlayerHandler(FallbackContentService fallback, RandomGenerator random) {
        this.fallback = fallback;
        this.random = random;
    }

    /** The moves to make now: each bot step is planned only once. Nothing while the game is paused. */
    List<Move> plan(RoomState r) {
        if (r.getPause() != null || !r.hasBots()) {
            return List.of();
        }
        List<Move> moves = new ArrayList<>();
        String game = "g" + r.getGameNumber();
        for (PlayerState bot : r.activePlayers()) {
            if (!bot.isBot()) {
                continue;
            }
            switch (r.getPhase()) {
                case INTAKE -> {
                    String step = "intake:" + game + ":" + bot.getId();
                    if (bot.getIntakeGame() == 0 && r.planBotStep(step)) {
                        moves.add(intake(r, bot, step));
                    }
                }
                case ROUND_VOTE -> {
                    String step = step(r, game, "kind", bot);
                    if (!r.getKindVotes().containsKey(bot.getId()) && r.planBotStep(step)) {
                        RoundKind kind = RoundKind.values()[random.nextInt(RoundKind.values().length)];
                        moves.add(move(bot, step, CommandType.ROUND_KIND_VOTE, data().put("kind", kind.name())));
                    }
                }
                case ANSWERING -> answers(r, game, bot, moves);
                case VOTING -> vote(r, game, bot).ifPresent(moves::add);
                default -> {
                    // Lobby, reveal and finale need nothing from a player.
                }
            }
        }
        return moves;
    }

    /**
     * What to try after a move was refused: intake answers the model couldn't check are sent again blank (a player
     * may skip them), so a game without a model doesn't wait for the bot. Anything else is left: the bot sits it out.
     */
    Optional<Move> afterRefusal(Move refused) {
        if (refused.command() == CommandType.INTAKE_SUBMIT
                && !refused.data().path("answers").path(0).asString("").isEmpty()) {
            return Optional.of(new Move(
                    refused.playerId(), refused.step(), CommandType.INTAKE_SUBMIT, blankAnswers(), refused.delayMs()));
        }
        return Optional.empty();
    }

    private Move intake(RoomState r, PlayerState bot, String step) {
        ObjectNode data = data();
        ArrayNode answers = data.putArray("answers");
        for (int i = 0; i < 3; i++) {
            answers.add(fallback.botAnswer(r.getSettings().language(), random));
        }
        return move(bot, step, CommandType.INTAKE_SUBMIT, data);
    }

    private void answers(RoomState r, String game, PlayerState bot, List<Move> moves) {
        if (r.getRound() == null) {
            return;
        }
        for (DuelState duel : r.getRound().getDuels()) {
            String step = step(r, game, "answer:" + duel.getId(), bot);
            if (duel.involves(bot.getId()) && duel.answerOf(bot.getId()) == null && r.planBotStep(step)) {
                moves.add(move(
                        bot,
                        step,
                        CommandType.ANSWER_SUBMIT,
                        data().put("duelId", duel.getId())
                                .put("text", fallback.botAnswer(r.getSettings().language(), random))));
            }
        }
    }

    private Optional<Move> vote(RoomState r, String game, PlayerState bot) {
        RoundState round = r.getRound();
        VoteStepState ballot = round == null ? null : round.currentVote();
        if (round == null
                || ballot == null
                || !ballot.getEligible().contains(bot.getId())
                || ballot.getVotes().containsKey(bot.getId())) {
            return Optional.empty();
        }
        List<String> others =
                ballot.getOptions().stream().filter(o -> !o.equals(bot.getId())).toList();
        List<String> choices = others.isEmpty() ? ballot.getOptions() : others;
        String step = step(r, game, "vote:" + round.getDuelIndex(), bot);
        if (choices.isEmpty() || !r.planBotStep(step)) {
            return Optional.empty();
        }
        String option = choices.get(random.nextInt(choices.size()));
        return Optional.of(move(bot, step, CommandType.VOTE_SUBMIT, data().put("optionId", option)));
    }

    private static String step(RoomState r, String game, String what, PlayerState bot) {
        return what + ":" + game + ":r" + r.getRoundNumber() + ":" + bot.getId();
    }

    private Move move(PlayerState bot, String step, CommandType command, ObjectNode data) {
        return new Move(bot.getId(), step, command, data, MIN_DELAY_MS + random.nextLong(MAX_DELAY_MS - MIN_DELAY_MS));
    }

    private static ObjectNode data() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static ObjectNode blankAnswers() {
        ObjectNode data = data();
        data.putArray("answers").add("").add("").add("");
        return data;
    }
}
