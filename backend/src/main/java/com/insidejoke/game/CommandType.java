package com.insidejoke.game;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every command a client can send over the WebSocket, by its name in the protocol, with the permission it needs.
 * Each has exactly one {@link CommandHandler} (Command pattern); the engine checks that at startup.
 */
public enum CommandType {
    GAME_START("game.start", Action.CONTROL_GAME),
    GAME_NEXT("game.next", Action.CONTROL_GAME),
    GAME_AGAIN("game.again", Action.CONTROL_GAME),
    GAME_END("game.end", Action.MODERATE_ROOM),
    GAME_PAUSE("game.pause", Action.MODERATE_ROOM),
    GAME_RESUME("game.resume", Action.MODERATE_ROOM),
    GAME_SETTINGS("game.settings", Action.CHANGE_SETTINGS),
    PAYWALL_DISMISS("paywall.dismiss", Action.MODERATE_ROOM),
    PLAYER_KICK("player.kick", Action.MODERATE_ROOM),
    CAPTAIN_SET("captain.set", Action.MODERATE_ROOM),
    ROOM_LOCK("room.lock", Action.MODERATE_ROOM),
    ROOM_CLOSE("room.close", Action.MODERATE_ROOM),
    INTAKE_SUBMIT("intake.submit", Action.PLAY),
    DOSSIER_ADD("dossier.add", Action.PLAY),
    ROUND_KIND_VOTE("round.kind.vote", Action.VOTE_ROUND_KIND),
    ANSWER_SUBMIT("answer.submit", Action.PLAY),
    VOTE_SUBMIT("vote.submit", Action.PLAY),
    AUDIENCE_VOTE("audience.vote", Action.AUDIENCE_VOTE),
    /** The player a line is about, or a moderator, may skip it: the handler checks which. */
    LINE_SKIP("line.skip", null),
    FEEDBACK_QUICK("feedback.quick", Action.PLAY);

    private final String wire;
    private final Action action;

    CommandType(String wire, Action action) {
        this.wire = wire;
        this.action = action;
    }

    /** The name in the protocol ({@code game.start}). */
    public String wire() {
        return wire;
    }

    /** The permission the sender needs, or empty when the handler decides. */
    public Optional<Action> action() {
        return Optional.ofNullable(action);
    }

    public static Optional<CommandType> fromWire(String name) {
        return Arrays.stream(values()).filter(c -> c.wire.equals(name)).findFirst();
    }
}
