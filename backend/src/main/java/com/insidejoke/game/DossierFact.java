package com.insidejoke.game;

/** A secret given to the AI host. Never shown to any client and deleted with the room. */
public record DossierFact(String id, String authorId, String aboutPlayerId, String text) {}
