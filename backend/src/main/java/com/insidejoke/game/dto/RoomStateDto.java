package com.insidejoke.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.game.Phase;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoomStateDto(
        long version,
        long serverTime,
        String code,
        Phase phase,
        Long deadline,
        PausedDto paused,
        WaitingDto waiting,
        boolean thinking,
        boolean locked,
        RoomSettingsDto settings,
        LobbyDto lobby,
        List<PlayerDto> players,
        YouDto you,
        HostDto host,
        IntakeDto intake,
        KindVoteDto kindVote,
        RoundDto round,
        FinaleDto finale,
        Integer secrets,
        Boolean secretsOpen,
        boolean starting,
        ErrorCode paywall,
        String sessionId,
        AudienceDto audience) {}
