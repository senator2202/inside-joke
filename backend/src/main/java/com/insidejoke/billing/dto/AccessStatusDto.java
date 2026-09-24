package com.insidejoke.billing.dto;

import com.insidejoke.common.ErrorCode;
import java.time.Instant;
import java.util.List;

/** Access summary for the host's pages (H3, H6) and the post-payment poll (H5b). */
public record AccessStatusDto(
        boolean freeGamesEnabled,
        boolean freeGameAvailable,
        Instant nextFreeGameAt,
        List<PassStatusDto> passes,
        String nextGame,
        ErrorCode paywallReason) {}
