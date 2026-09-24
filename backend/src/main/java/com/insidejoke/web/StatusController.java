package com.insidejoke.web;

import com.insidejoke.settings.AppSettingsService;
import com.insidejoke.web.dto.StatusDto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public service status for the landing page (X3): whether new rooms can be created and free games are on. */
@RestController
public class StatusController {

    private final AppSettingsService settings;
    private final String version;

    /** The version comes from Maven's build-info; a build without it (running straight from an IDE) reports "dev". */
    public StatusController(AppSettingsService settings, ObjectProvider<BuildProperties> build) {
        this.settings = settings;
        BuildProperties info = build.getIfAvailable();
        this.version = info == null ? "dev" : info.getVersion();
    }

    @GetMapping("/api/status")
    public StatusDto status() {
        AppSettingsService.Snapshot s = settings.get();
        return new StatusDto(s.drainMode(), s.freeGamesEnabled(), version);
    }
}
