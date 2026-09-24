package com.insidejoke.game;

/** Holder of a room token: the owner's screen, a player, a remote screen copy or a stream viewer. */
public record Member(String token, MemberKind kind, String playerId, String audienceId) {}
