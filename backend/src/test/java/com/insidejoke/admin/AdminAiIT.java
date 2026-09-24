package com.insidejoke.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.ai.AiCallEntity;
import com.insidejoke.ai.AiCallRepository;
import com.insidejoke.ai.AiOutcome;
import com.insidejoke.ai.AiPurpose;
import com.insidejoke.billing.Product;
import com.insidejoke.game.HostAiService;
import com.insidejoke.moderation.ModerationStage;
import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.ApiClient;
import com.insidejoke.support.FakeAi;
import com.insidejoke.support.FakeHttp;
import com.insidejoke.support.Party;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class AdminAiIT extends AbstractIntegrationTest {

    @Autowired
    HostAiService ai;

    @Autowired
    AiCallRepository calls;

    private static HostAiService.CallContext context() {
        return new HostAiService.CallContext(
                null, false, new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
    }

    private ApiClient admin() {
        return Party.signIn(port, json, FAKE, "boss@example.com");
    }

    @Test
    void theStatusShowsTheKeyTheLastCallAndLiveRateLimits() {
        ApiClient admin = admin();
        FAKE.on(
                "POST",
                "/anthropic/v1/messages",
                req -> FakeHttp.Reply.json(
                                400,
                                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                                        + "\"message\":\"Your credit balance is too low to access the Anthropic API.\"}}")
                        .withHeaders(Map.of(
                                "anthropic-ratelimit-requests-limit",
                                "50",
                                "anthropic-ratelimit-requests-remaining",
                                "49",
                                "anthropic-ratelimit-requests-reset",
                                "2031-03-07T12:00:30Z",
                                "anthropic-ratelimit-input-tokens-limit",
                                "50000",
                                "anthropic-ratelimit-input-tokens-remaining",
                                "48000",
                                "anthropic-ratelimit-output-tokens-limit",
                                "10000",
                                "anthropic-ratelimit-output-tokens-remaining",
                                "9900")));
        ai.moderate(context(), ModerationStage.DOSSIER, List.of("Sings in the shower"));

        JsonNode status = admin.get("/api/admin/ai/status").json();
        assertThat(status.path("keyConfigured").asBoolean()).isTrue();
        assertThat(status.path("model").asString()).isNotBlank();
        JsonNode failure = status.path("lastFailure");
        assertThat(failure.path("outcome").asString()).isEqualTo("ERROR");
        assertThat(failure.path("purpose").asString()).isEqualTo("MODERATION");
        assertThat(failure.path("error").asString())
                .isEqualTo(
                        "Anthropic HTTP 400: invalid_request_error: Your credit balance is too low to access the Anthropic API.");
        JsonNode limits = status.path("rateLimits");
        assertThat(limits.path("httpStatus").asInt()).isEqualTo(400);
        assertThat(limits.path("requests").path("limit").asLong()).isEqualTo(50);
        assertThat(limits.path("requests").path("remaining").asLong()).isEqualTo(49);
        assertThat(limits.path("requests").path("reset").asString()).isEqualTo("2031-03-07T12:00:30Z");
        assertThat(limits.path("inputTokens").path("remaining").asLong()).isEqualTo(48000);
        assertThat(limits.path("outputTokens").path("limit").asLong()).isEqualTo(10000);
        assertThat(limits.path("tokens").isNull()).as("not sent, so not shown").isTrue();

        FakeAi.install(FAKE);
        ai.moderate(context(), ModerationStage.DOSSIER, List.of("Collects spoons"));
        JsonNode after = admin.get("/api/admin/ai/status").json();
        assertThat(after.path("lastCall").path("outcome").asString()).isEqualTo("OK");
        assertThat(after.path("lastFailure").path("error").asString()).contains("credit balance is too low");
    }

    @Test
    void theCallLogFiltersAndPagesNewestFirst() {
        ApiClient admin = admin();
        UUID host = data.host().id();
        UUID game = data.game(host, data.pass(host, Product.PARTY_PASS), clock.instant());
        Instant base = Instant.parse("2021-03-03T10:00:00Z");
        List<AiCallEntity> rows = new ArrayList<>(List.of(
                new AiCallEntity(
                        game,
                        AiPurpose.ROUND_GEN,
                        "anthropic",
                        "claude-test",
                        "round_gen.v2",
                        900,
                        300,
                        null,
                        1200,
                        1400,
                        AiOutcome.OK,
                        false),
                new AiCallEntity(
                        game,
                        AiPurpose.ROUND_GEN,
                        "anthropic",
                        "claude-test",
                        "round_gen.v2",
                        null,
                        null,
                        null,
                        0,
                        8000,
                        AiOutcome.TIMEOUT,
                        false,
                        "Anthropic timed out"),
                new AiCallEntity(
                        game,
                        AiPurpose.HOST_LINE,
                        "anthropic",
                        "claude-test",
                        "duel_review.v2",
                        500,
                        90,
                        null,
                        950,
                        900,
                        AiOutcome.INVALID_JSON,
                        false,
                        "Output rejected: missing duels"),
                new AiCallEntity(
                        null,
                        AiPurpose.MODERATION,
                        "anthropic",
                        "claude-test",
                        "moderation.v2",
                        null,
                        null,
                        null,
                        0,
                        120,
                        AiOutcome.ERROR,
                        true,
                        "Anthropic HTTP 529: overloaded_error: Overloaded"),
                new AiCallEntity(
                        game,
                        AiPurpose.MODERATION,
                        "anthropic",
                        "claude-test",
                        "moderation.v2",
                        null,
                        null,
                        null,
                        0,
                        0,
                        AiOutcome.FALLBACK,
                        false,
                        "The room's moderation budget is used up: not called."),
                new AiCallEntity(
                        game, AiPurpose.TTS, "tts", "tts-1", null, null, null, 64, 960, 700, AiOutcome.OK, false),
                new AiCallEntity(
                        game,
                        AiPurpose.FINALE,
                        "anthropic",
                        "claude-test",
                        "finale.v2",
                        700,
                        200,
                        null,
                        1700,
                        1100,
                        AiOutcome.OK,
                        false)));
        for (int i = 0; i < rows.size(); i++) {
            calls.insert(rows.get(i), base.plusSeconds(60L * i));
        }
        String range = "from=2021-03-03&to=2021-03-03";

        JsonNode first = admin.get("/api/admin/ai/calls?" + range + "&size=3").json();
        assertThat(first.path("totalItems").asLong()).isEqualTo(7);
        assertThat(first.path("totalPages").asInt()).isEqualTo(3);
        assertThat(first.path("items").get(0).path("purpose").asString())
                .as("newest first")
                .isEqualTo("FINALE");
        assertThat(first.path("items").get(0).path("roomCode").asString()).isEqualTo("TEST");
        assertThat(admin.get("/api/admin/ai/calls?" + range + "&size=3&page=2")
                        .json()
                        .path("items")
                        .size())
                .isEqualTo(1);

        JsonNode failures =
                admin.get("/api/admin/ai/calls?" + range + "&outcome=failures").json();
        assertThat(failures.path("totalItems").asLong())
                .as("ERROR, TIMEOUT and INVALID_JSON")
                .isEqualTo(3);
        List<String> errors = new ArrayList<>();
        failures.path("items").forEach(i -> errors.add(i.path("error").asString()));
        assertThat(errors)
                .containsExactly(
                        "Anthropic HTTP 529: overloaded_error: Overloaded",
                        "Output rejected: missing duels",
                        "Anthropic timed out");

        JsonNode moderation = admin.get("/api/admin/ai/calls?" + range + "&purpose=MODERATION")
                .json();
        assertThat(moderation.path("totalItems").asLong()).isEqualTo(2);
        JsonNode fallback = admin.get("/api/admin/ai/calls?" + range + "&outcome=FALLBACK")
                .json()
                .path("items")
                .get(0);
        assertThat(fallback.path("error").asString()).contains("budget is used up");
        JsonNode tts = admin.get("/api/admin/ai/calls?" + range + "&purpose=tts")
                .json()
                .path("items")
                .get(0);
        assertThat(tts.path("provider").asString()).isEqualTo("tts");
        assertThat(tts.path("ttsChars").asInt()).isEqualTo(64);
        assertThat(tts.path("costMicros").asLong()).isEqualTo(960);
        assertThat(admin.get("/api/admin/ai/calls?from=2021-03-04&to=2021-03-10")
                        .json()
                        .path("totalItems")
                        .asLong())
                .isZero();
    }

    @Test
    void badFiltersAreRefusedAndHostsAreKeptOut() {
        ApiClient admin = admin();
        assertThat(admin.get("/api/admin/ai/calls?purpose=BOGUS").errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(admin.get("/api/admin/ai/calls?outcome=NOPE").errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(admin.get("/api/admin/ai/calls?from=2031-03-07&to=2031-03-01")
                        .errorCode())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(admin.get("/api/admin/ai/calls?size=500").errorCode()).isEqualTo("VALIDATION_FAILED");
        ApiClient host = Party.signIn(port, json, FAKE, uniqueEmail("curious"));
        assertThat(host.get("/api/admin/ai/status").status()).isEqualTo(403);
        assertThat(host.get("/api/admin/ai/calls").status()).isEqualTo(403);
    }
}
