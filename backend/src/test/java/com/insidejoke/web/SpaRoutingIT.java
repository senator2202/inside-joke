package com.insidejoke.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insidejoke.support.AbstractIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

class SpaRoutingIT extends AbstractIntegrationTest {

    @Test
    void clientRoutesServeTheSinglePageApp() throws Exception {
        for (String path : new String[] {
            "/",
            "/login",
            "/new",
            "/account",
            "/join",
            "/admin",
            "/auth/callback",
            "/screen/KWXB",
            "/j/KWXB",
            "/play/KWXB",
            "/view/KWXB",
            "/w/KWXB",
            "/some-unknown-page"
        }) {
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void unknownApiPathsAreJsonErrors() throws Exception {
        mvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/api/auth/config"))
                .andExpect(header().string("Content-Security-Policy", Matchers.containsString("default-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    void theAppShellIsNeverCachedButHashedAssetsAre() throws Exception {
        // After a deploy browsers must fetch the new index.html, or they would ask for asset names that no longer
        // exist.
        mvc.perform(get("/index.html"))
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-cache")));
    }
}
