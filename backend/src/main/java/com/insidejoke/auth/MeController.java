package com.insidejoke.auth;

import com.insidejoke.auth.dto.ProfileDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    private final CurrentUserService currentUser;
    private final UserService users;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public MeController(
            CurrentUserService currentUser,
            UserService users,
            FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.currentUser = currentUser;
        this.users = users;
        this.sessions = sessions;
    }

    @GetMapping("/api/me")
    public ProfileDto profile(@AuthenticationPrincipal AppPrincipal principal) {
        UserEntity user = currentUser.require(principal);
        return new ProfileDto(user.id(), user.email(), user.displayName(), user.role(), user.googleSub() != null);
    }

    /**
     * Deletes the account (H6): the email and sign-in details are anonymized and passes end without a refund; purchase
     * records stay for accounting. The host's open rooms close and every session of the account is signed out.
     */
    @DeleteMapping("/api/me")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppPrincipal principal, HttpServletRequest request) {
        UserEntity user = currentUser.require(principal);
        users.deleteAccount(user.id());
        sessions.findByPrincipalName(user.id().toString()).keySet().forEach(sessions::deleteById);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }
}
