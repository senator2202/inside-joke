package com.insidejoke.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.insidejoke.game.Role;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record YouDto(
        Role role,
        String playerId,
        String name,
        String emoji,
        Integer score,
        Integer rank,
        Boolean canVote,
        Boolean voted,
        String myVote,
        Integer secretsLeft,
        Boolean intakeNeeded,
        List<AssignmentDto> assignments,
        Boolean subject,
        Boolean inDuel,
        Integer roundPoints,
        String title) {}
