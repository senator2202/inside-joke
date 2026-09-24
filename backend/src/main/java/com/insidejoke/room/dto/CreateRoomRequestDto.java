package com.insidejoke.room.dto;

public record CreateRoomRequestDto(
        String tone, String length, String mode, Boolean hideCode, Boolean adultsConfirmed, String language) {}
