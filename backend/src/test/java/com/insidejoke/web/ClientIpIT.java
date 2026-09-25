package com.insidejoke.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Behind a public proxy the client is the first address from the right in X-Forwarded-For that isn't a known proxy
 * (docs/AUDIT.md, item 8). Seen through the per-IP limit on room lookups: 60 a minute.
 */
@TestPropertySource(properties = "server.tomcat.remoteip.trusted-proxies=173.245.48.0/20,2400:cb00::/32")
class ClientIpIT extends AbstractIntegrationTest {

    private static final String CLOUDFLARE_EDGE = "173.245.48.10";

    private final HttpClient http = HttpClient.newHttpClient();

    private int lookup(String forwardedFor) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/rooms/ZZZZ"))
                .header("X-Forwarded-For", forwardedFor)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private void useUpTheLimit(String forwardedFor) throws Exception {
        for (int i = 0; i < 60; i++) {
            assertThat(lookup(forwardedFor)).as("lookup %d", i + 1).isEqualTo(404);
        }
        assertThat(lookup(forwardedFor)).as("the 61st lookup").isEqualTo(429);
    }

    @Test
    void clientsBehindTheSameCloudflareEdgeHaveTheirOwnLimits() throws Exception {
        useUpTheLimit("203.0.113.7, " + CLOUDFLARE_EDGE);
        assertThat(lookup("203.0.113.8, " + CLOUDFLARE_EDGE))
                .as("another client, same edge")
                .isEqualTo(404);
    }

    @Test
    void anAddressTheClientAddsOnTheLeftDoesNotResetItsLimit() throws Exception {
        useUpTheLimit("203.0.113.20, " + CLOUDFLARE_EDGE);
        assertThat(lookup("198.51.100.1, 203.0.113.20, " + CLOUDFLARE_EDGE)).isEqualTo(429);
    }

    @Test
    void anUnknownProxyInTheChainIsTreatedAsTheClient() throws Exception {
        useUpTheLimit("203.0.113.30, 192.0.2.50");
        assertThat(lookup("203.0.113.31, 192.0.2.50"))
                .as("same untrusted hop, so same limit")
                .isEqualTo(429);
    }
}
