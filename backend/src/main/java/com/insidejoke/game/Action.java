package com.insidejoke.game;

import java.util.EnumSet;
import java.util.Set;

/** Permission matrix from blueprint 5.3; skipping a host line is further limited to lines about oneself. */
public enum Action {
    CHANGE_SETTINGS(EnumSet.of(Role.OWNER_SCREEN)),
    CONTROL_GAME(EnumSet.of(Role.OWNER_SCREEN, Role.CAPTAIN)),
    MODERATE_ROOM(EnumSet.of(Role.OWNER_SCREEN)),
    PLAY(EnumSet.of(Role.CAPTAIN, Role.PLAYER)),
    VOTE_ROUND_KIND(EnumSet.of(Role.CAPTAIN, Role.PLAYER)),
    SKIP_ANY_LINE(EnumSet.of(Role.OWNER_SCREEN)),
    SKIP_OWN_LINE(EnumSet.of(Role.OWNER_SCREEN, Role.CAPTAIN, Role.PLAYER)),
    AUDIENCE_VOTE(EnumSet.of(Role.AUDIENCE));

    private final Set<Role> roles;

    Action(Set<Role> roles) {
        this.roles = roles;
    }

    public boolean allowedFor(Role role) {
        return roles.contains(role);
    }
}
