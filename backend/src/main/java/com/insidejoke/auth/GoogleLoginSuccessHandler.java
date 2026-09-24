package com.insidejoke.auth;

import com.insidejoke.common.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/** Maps the Google identity to an app account and replaces the OAuth token with the app session principal. */
@Component
public class GoogleLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserService users;
    private final SessionLoginService sessionLogin;
    private final LoginEventService events;
    private final AppProperties props;

    /** Redirects go to the public URL, not the host that received Google's callback (see vite.config.ts). */
    public GoogleLoginSuccessHandler(
            UserService users, SessionLoginService sessionLogin, LoginEventService events, AppProperties props) {
        this.users = users;
        this.sessionLogin = sessionLogin;
        this.events = events;
        this.props = props;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication auth)
            throws IOException {
        if (!(auth.getPrincipal() instanceof OidcUser oidc)
                || oidc.getEmail() == null
                || !Boolean.TRUE.equals(oidc.getEmailVerified())) {
            request.getSession().invalidate();
            response.sendRedirect(props.publicUrl() + "/login?error=google_email");
            return;
        }
        UserEntity user = users.loginWithGoogle(oidc.getSubject(), oidc.getEmail(), oidc.getFullName());
        sessionLogin.establish(user, request, response);
        events.loggedIn(user, "google");
        response.sendRedirect(props.publicUrl() + "/auth/callback?provider=google");
    }
}
