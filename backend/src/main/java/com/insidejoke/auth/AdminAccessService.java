package com.insidejoke.auth;

import com.insidejoke.common.AppProperties;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Service;

/**
 * Who may use the admin panel, decided on every request: an active account whose current address is in
 * APP_ADMIN_EMAILS. The role saved in the session at sign-in is never trusted for this, so an address removed from the
 * list loses access on its next request, not when the session expires (docs/AUDIT.md, item 16).
 */
@Service
public class AdminAccessService {

    private final UserRepository users;
    private final AppProperties props;

    public AdminAccessService(UserRepository users, AppProperties props) {
        this.users = users;
        this.props = props;
    }

    public boolean isAdmin(AppPrincipal principal) {
        return principal != null
                && users.findActiveById(principal.id())
                        .map(user -> props.isAdminEmail(user.email()))
                        .orElse(false);
    }

    /** The access rule for /api/admin/**; anonymous requests get 401 from the entry point as before. */
    public AuthorizationManager<RequestAuthorizationContext> adminPaths() {
        return (authentication, context) -> {
            var auth = authentication.get();
            return new AuthorizationDecision(
                    auth != null && auth.getPrincipal() instanceof AppPrincipal p && isAdmin(p));
        };
    }
}
