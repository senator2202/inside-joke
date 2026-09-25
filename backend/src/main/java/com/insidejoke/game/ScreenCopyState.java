package com.insidejoke.game;

import java.time.Instant;

/**
 * A remote copy of the shared screen (user flow R1): how many sockets use its token now, and since when none does.
 * Mutable, only touched under the room lock. Several tabs of one browser share the token, so connections are counted.
 */
final class ScreenCopyState {

    private int connections;
    private Instant idleSince;

    /** A copy counts as idle from the moment it is issued until its first socket says hello. */
    ScreenCopyState(Instant issuedAt) {
        this.idleSince = issuedAt;
    }

    void connected() {
        connections++;
        idleSince = null;
    }

    void disconnected(Instant now) {
        connections = Math.max(0, connections - 1);
        if (connections == 0) {
            idleSince = now;
        }
    }

    /** When the last socket of this copy went away (or the copy was issued); null while a socket is connected. */
    Instant getIdleSince() {
        return idleSince;
    }
}
