package com.insidejoke.billing;

import com.insidejoke.auth.AppPrincipal;
import com.insidejoke.billing.dto.AccessDto;
import com.insidejoke.settings.AppSettingsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the host can play next (H3 status line, H6 pass list, H5b activation poll). */
@RestController
public class PassController {

    private final GameAccessService access;
    private final AppSettingsService settings;

    public PassController(GameAccessService access, AppSettingsService settings) {
        this.access = access;
        this.settings = settings;
    }

    @GetMapping("/api/billing/passes")
    public AccessDto entitlements(@AuthenticationPrincipal AppPrincipal user) {
        return new AccessDto(access.status(user.id()), settings.get().drainMode());
    }
}
