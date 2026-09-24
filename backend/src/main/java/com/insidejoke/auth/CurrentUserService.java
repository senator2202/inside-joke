package com.insidejoke.auth;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import org.springframework.stereotype.Service;

/** Resolves the session principal to a live account; deleted accounts lose access immediately. */
@Service
public class CurrentUserService {

    private final UserService users;

    public CurrentUserService(UserService users) {
        this.users = users;
    }

    public UserEntity require(AppPrincipal principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return users.findActive(principal.id()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }
}
