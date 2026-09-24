package com.insidejoke.auth;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/** The signed-in host stored in the server-side session (Spring Session JDBC serializes it). */
public record AppPrincipal(UUID id, String email, String role) implements Serializable, Principal {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The session's principal name is the user id: short enough for Spring Session's indexed column and stable, so all
     * of a user's sessions can be found (and ended when the account is deleted).
     */
    @Override
    public String getName() {
        return id.toString();
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
