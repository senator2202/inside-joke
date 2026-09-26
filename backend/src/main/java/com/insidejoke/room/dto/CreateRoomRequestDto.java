package com.insidejoke.room.dto;

public record CreateRoomRequestDto(
        String tone,
        String length,
        String mode,
        Boolean hideCode,
        Boolean adultsConfirmed,
        String language,
        // Who came (FRIENDS by default) and an optional line about the group (roadmap R39).
        String company,
        String context) {}
