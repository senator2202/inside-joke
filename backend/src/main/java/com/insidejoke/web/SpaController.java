package com.insidejoke.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the single-page app for client-side routes. API, WebSocket and actuator paths are excluded,
 * unknown client routes are rendered by the SPA's own "page not found" screen.
 */
@Controller
public class SpaController {

    @GetMapping({
        "/",
        "/{first:(?!api$|ws$|actuator$|assets$|oauth2$)[^.]+}",
        "/{first:(?!api$|ws$|actuator$|assets$|oauth2$|login$)[^.]+}/{second:[^.]+}"
    })
    public String index() {
        return "forward:/index.html";
    }
}
