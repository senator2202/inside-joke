package com.insidejoke.game;

import com.insidejoke.analytics.AnalyticsService;
import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.common.Language;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import tools.jackson.databind.JsonNode;

/**
 * Lobby commands of the owner and captain: starting a game, playing again, removing a player, changing settings.
 * Runs under the room lock taken by {@link GameEngineService}.
 */
final class LobbyPhaseHandler {

    private final GameRuntimeService runtime;
    private final GameProperties props;
    private final Clock clock;
    private final FallbackContentService fallback;
    private final GameAccessPort access;
    private final AnalyticsService analytics;
    private final RoomEventListener events;
    private final RandomGenerator random;

    LobbyPhaseHandler(
            GameRuntimeService runtime,
            GameProperties props,
            Clock clock,
            FallbackContentService fallback,
            GameAccessPort access,
            AnalyticsService analytics,
            RoomEventListener events,
            RandomGenerator random) {
        this.runtime = runtime;
        this.props = props;
        this.clock = clock;
        this.fallback = fallback;
        this.access = access;
        this.analytics = analytics;
        this.events = events;
        this.random = random;
    }

    void changeSettings(RoomState r, JsonNode data) {
        if (r.getPhase() != Phase.LOBBY || r.isStarting()) {
            throw new ApiException(ErrorCode.INVALID_PHASE, "Settings can only change in the lobby.");
        }
        Tone tone = GameRuleUtils.enumValue(
                Tone.class, data.path("tone").asString(r.getSettings().tone().name()));
        GameLength length = GameRuleUtils.enumValue(
                GameLength.class,
                data.path("length").asString(r.getSettings().length().name()));
        boolean hideCode = data.path("hideCode").asBoolean(r.getSettings().hideCode());
        Language language = data.has("language")
                ? Language.fromCode(data.path("language").asString(""))
                        .orElseThrow(() -> new ApiException(
                                ErrorCode.VALIDATION_FAILED,
                                "Choose en or ru.",
                                Map.of("fields", Map.of("language", "en or ru"))))
                : r.getSettings().language();
        if (tone == Tone.SPICY
                && r.getSettings().tone() != Tone.SPICY
                && !data.path("adultsConfirmed").asBoolean(false)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Confirm that every player is over 18.",
                    Map.of("fields", Map.of("adultsConfirmed", "required")));
        }
        boolean contentChanged =
                tone != r.getSettings().tone() || language != r.getSettings().language();
        r.setSettings(new RoomSettings(
                tone,
                length,
                r.getSettings().mode(),
                hideCode && r.getSettings().mode() == RoomMode.STREAMER,
                language));
        if (contentChanged && r.getGameNumber() == 0) {
            r.setIntakeQuestions(fallback.intakeQuestions(language, tone, random));
        }
    }
    // ------------------------------------------------------------------ lifecycle: start, play again, finale, close

    void startGame(RoomState r) {
        GameRuleUtils.requirePhase(r, Phase.LOBBY);
        if (r.isStarting()) {
            return;
        }
        List<PlayerState> present = runtime.presentPlayers(r);
        if (present.size() < props.minPlayers()) {
            throw new ApiException(ErrorCode.NOT_ENOUGH_PLAYERS);
        }
        r.setStarting(true);
        r.setPaywall(null);
        GameAccessPort.StartParams request = new GameAccessPort.StartParams(
                r.getOwnerUserId(),
                r.getLastSessionId(),
                r.getCode(),
                r.getSettings().mode(),
                r.getSettings().tone(),
                r.getSettings().length(),
                GameEngineService.PROMPT_VERSION,
                present.size(),
                r.getSettings().language());
        runtime.async(r, () -> access.start(request), (room, outcome) -> {
            room.setStarting(false);
            if (outcome.error() instanceof ApiException denied) {
                room.setPaywall(denied.code());
                analytics.track(
                        "paywall_shown",
                        room.getOwnerUserId().toString(),
                        Map.of("reason", denied.code().name()));
                return;
            }
            if (outcome.error() != null || room.getPhase() != Phase.LOBBY) {
                room.setPaywall(null);
                return;
            }
            GameAccessPort.Started started = outcome.value();
            room.setSessionId(started.sessionId());
            room.setFreeGame(started.free());
            analytics.track(
                    "game_started",
                    room.getOwnerUserId().toString(),
                    Map.of(
                            "is_free",
                            started.free(),
                            "players",
                            runtime.presentPlayers(room).size(),
                            "pass",
                            started.passType() == null ? "FREE" : started.passType()));
            beginGame(room);
        });
    }

    void beginGame(RoomState r) {
        r.addGameNumber(1);
        r.setRoundNumber(0);
        r.setRoundsTotal(r.getSettings().length().rounds());
        r.setRound(null);
        r.clearKindCount();
        r.clearKindVote();
        r.setFinale(null);
        r.setFinaleRequested(false);
        r.setAnswerOfNightVotes(-1);
        r.setAnswerOfNightText(null);
        r.getLlmCalls().set(0);
        r.getTtsCalls().set(0);
        r.getModerationCalls().set(0);
        r.getPlayers().values().forEach(PlayerState::resetForNewGame);
        boolean intakeNeeded = r.activePlayers().stream().anyMatch(p -> p.getIntakeGame() == 0);
        if (intakeNeeded) {
            r.setPhase(Phase.INTAKE);
            r.setDeadlineMs(runtime.now() + props.intake().toMillis());
            runtime.say(r, runtime.line(r, "intakeStart", Map.of()), Set.of());
        } else {
            runtime.startRound(r, 1);
        }
        runtime.checkPlayerCount(r);
    }

    void playAgain(RoomState r) {
        GameRuleUtils.requirePhase(r, Phase.FINALE);
        Instant now = clock.instant();
        for (PlayerState p : r.activePlayers()) {
            if (!p.present(now, props.disconnectGrace())) {
                p.setRemoved(true);
            }
        }
        r.setPhase(Phase.LOBBY);
        r.setDeadlineMs(null);
        r.setRound(null);
        r.setSessionId(null);
        r.setPaywall(null);
        r.setPending(null);
        r.setThinkingUntilMs(null);
        r.getPlayers().values().forEach(PlayerState::resetForNewGame);
        r.setFinale(null);
        r.setFinaleRequested(false);
        runtime.say(r, runtime.line(r, "lobbyWaiting", Map.of()), Set.of());
        analytics.track("play_again_clicked", r.getOwnerUserId().toString(), Map.of("room", r.getCode()));
    }

    void kick(RoomState r, String playerId) {
        PlayerState p = GameRuleUtils.activePlayer(r, playerId);
        p.setRemoved(true);
        if (playerId.equals(r.getCaptainId())) {
            r.setCaptainId(r.activePlayers().stream()
                    .filter(PlayerState::connected)
                    .map(PlayerState::getId)
                    .findFirst()
                    .orElse(r.activePlayers().stream()
                            .map(PlayerState::getId)
                            .findFirst()
                            .orElse(null)));
        }
        events.kicked(r, playerId);
        if (r.getPhase() == Phase.VOTING && r.getRound() != null && r.getRound().currentVote() != null) {
            r.getRound().currentVote().removeEligible(playerId);
        }
        runtime.checkPlayerCount(r);
    }
}
