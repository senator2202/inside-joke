package com.insidejoke.game;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Phase timings and room limits (blueprint 4.3, 4.6, 9.3 and user flow section 9). */
@ConfigurationProperties("app.game")
public record GameProperties(
        @DefaultValue("90s") Duration intake,
        @DefaultValue("15s") Duration roundVote,
        @DefaultValue("60s") Duration answering,
        @DefaultValue("20s") Duration voting,
        @DefaultValue("12s") Duration reveal,
        @DefaultValue("5s") Duration hostThinking,
        @DefaultValue("20s") Duration disconnectGrace,
        @DefaultValue("10s") Duration screenAutoPause,
        @DefaultValue("60s") Duration waitForPlayers,
        @DefaultValue("30m") Duration idleClose,
        @DefaultValue("4h") Duration maxAge,
        @DefaultValue("2m") Duration closedRetention,
        @DefaultValue("8") int maxPlayers,
        @DefaultValue("3") int minPlayers,
        @DefaultValue("120") int maxAnswerChars,
        @DefaultValue("200") int maxFactChars,
        @DefaultValue("10") int maxFactsPerPlayer,
        @DefaultValue("12") int maxNameChars,
        @DefaultValue("40") int roomLlmBudget,
        @DefaultValue("60") int roomTtsBudget,
        // Checks of secrets and intake answers per room and game, apart from content generation.
        @DefaultValue("200") int roomModerationBudget,
        // Per player and game, so one player can't use up everyone's checks (8 × 25 = the room budget).
        @DefaultValue("25") int maxModerationChecksPerPlayer) {}
