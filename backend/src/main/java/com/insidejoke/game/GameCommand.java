package com.insidejoke.game;

import tools.jackson.databind.JsonNode;

/** One command from a client, with who sent it and how to answer. */
public record GameCommand(RoomState room, Member member, Role role, JsonNode data, ReplyHandler reply) {

    /** The sending player. */
    public PlayerState player() {
        return room.getPlayers().get(member.playerId());
    }

    /** A text field of the command's data, empty when absent. */
    public String text(String field) {
        return data.path(field).asString("");
    }
}
