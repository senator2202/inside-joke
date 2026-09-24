package com.insidejoke.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.FakeHttp;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class AnalyticsServiceIT extends AbstractIntegrationTest {

    @Autowired
    AnalyticsService analytics;

    private List<JsonNode> sentEvents() {
        return FAKE.requests("POST", "/posthog/batch/").stream()
                .flatMap(r -> {
                    JsonNode body = json.readTree(r.body());
                    assertThat(body.path("api_key").asString()).isEqualTo("test-posthog-key");
                    return StreamSupport.stream(body.path("batch").spliterator(), false);
                })
                .toList();
    }

    @Test
    void browserEventsAreRelayedFromAnAllowList() {
        analytics.flush();
        FAKE.reset();
        FAKE.on("POST", "/posthog/batch/", r -> FakeHttp.Reply.json(200, "{}"));
        assertThat(client().postWithoutCsrf(
                                "/api/events",
                                Map.of(
                                        "event",
                                        "landing_viewed",
                                        "anonymousId",
                                        "anon-12345678",
                                        "properties",
                                        Map.of("ref", "S9", "nested", Map.of("x", 1))))
                        .status())
                .isEqualTo(202);
        assertThat(client().post("/api/events", Map.of("event", "purchase_completed"))
                        .errorCode())
                .isEqualTo("VALIDATION_FAILED");
        analytics.flush();
        List<JsonNode> events = sentEvents();
        assertThat(events).hasSize(1);
        JsonNode e = events.getFirst();
        assertThat(e.path("event").asString()).isEqualTo("landing_viewed");
        assertThat(e.path("distinct_id").asString()).isEqualTo("anon:anon-12345678");
        assertThat(e.path("properties").path("ref").asString()).isEqualTo("S9");
        assertThat(e.path("properties").has("nested")).isFalse();
    }

    @Test
    void anOutageDropsEventsWithoutFailing() {
        analytics.flush();
        FAKE.reset();
        FAKE.on("POST", "/posthog/batch/", r -> FakeHttp.Reply.json(503, "{}"));
        analytics.track("room_created", "u1", Map.of());
        analytics.flush();
        FAKE.reset();
        FAKE.on("POST", "/posthog/batch/", r -> FakeHttp.Reply.json(200, "{}"));
        analytics.flush();
        assertThat(sentEvents()).isEmpty();
    }
}
