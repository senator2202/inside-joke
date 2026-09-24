package com.insidejoke.support;

import com.insidejoke.ai.AiSpendService;
import com.insidejoke.settings.AppSettingsService;
import com.insidejoke.web.RateLimitService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for integration tests: full application on a random port, embedded PostgreSQL,
 * and every external API redirected to {@link FakeHttp}. All subclasses share one Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestBeans.class, TestData.class})
public abstract class AbstractIntegrationTest {

    protected static final FakeHttp FAKE = FakeHttp.INSTANCE;

    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        String fake = FAKE.baseUrl();
        registry.add("spring.datasource.url", EmbeddedPg::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("app.mail.resend.base-url", () -> fake + "/resend");
        registry.add("app.google.authorization-uri", () -> fake + "/google/authorize");
        registry.add("app.google.token-uri", () -> fake + "/google/token");
        registry.add("app.google.jwk-set-uri", () -> fake + "/google/jwks");
        registry.add("app.google.user-info-uri", () -> fake + "/google/userinfo");
        registry.add("app.google.issuer-uri", () -> fake + "/google");
        registry.add("app.ai.anthropic.base-url", () -> fake + "/anthropic");
        // Tests that run the app without a model set this to "" before their context starts (see NoAiKeyIT).
        registry.add(
                "app.ai.anthropic.api-key", () -> System.getProperty("test.anthropic.api-key", "test-anthropic-key"));
        registry.add("app.ai.tts.base-url", () -> fake + "/tts");
        registry.add("app.ai.tts.api-key", () -> "test-tts-key");
        registry.add("app.posthog.host", () -> fake + "/posthog");
        registry.add("app.posthog.api-key", () -> "test-posthog-key");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected RateLimitService rateLimiter;

    @Autowired
    protected TestClock clock;

    @Autowired
    protected TestData data;

    @Autowired
    protected AppSettingsService settings;

    @Autowired
    protected AiSpendService aiSpend;

    @BeforeEach
    void resetSharedState() {
        FAKE.reset();
        FAKE.on("POST", "/posthog/batch/", r -> FakeHttp.Reply.json(200, "{\"status\":1}"));
        rateLimiter.reset();
        clock.reset();
        jdbc.sql("UPDATE app_setting SET value = CASE key "
                        + "WHEN 'free_games_enabled' THEN 'true'::jsonb WHEN 'tts_enabled' THEN 'true'::jsonb "
                        + "WHEN 'daily_free_ai_budget_micros' THEN '50000000'::jsonb WHEN 'audience_cap' THEN '2000'::jsonb "
                        + "WHEN 'drain_mode' THEN 'false'::jsonb ELSE value END")
                .update();
        settings.refresh();
        aiSpend.invalidate();
    }

    protected ApiClient client() {
        return new ApiClient(port, json);
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }
}
