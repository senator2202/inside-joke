package com.insidejoke.billing;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Paddle notifications. The signature is checked on the raw bytes, the event is stored before it is processed, and a
 * repeated event id is acknowledged without doing anything twice. Events that can never succeed are kept with the error
 * for a manual look and acknowledged, so Paddle stops retrying; unexpected failures answer 500 so Paddle retries.
 */
@RestController
public class PaddleWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaddleWebhookController.class);
    private static final String PROVIDER = PaddleEventService.PROVIDER;

    private final PaddleProperties paddle;
    private final WebhookEventRepository events;
    private final PaddleEventService processor;
    private final JsonMapper json;
    private final Clock clock;

    public PaddleWebhookController(
            PaddleProperties paddle,
            WebhookEventRepository events,
            PaddleEventService processor,
            JsonMapper json,
            Clock clock) {
        this.paddle = paddle;
        this.events = events;
        this.processor = processor;
        this.json = json;
        this.clock = clock;
    }

    @PostMapping("/api/webhooks/paddle")
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "Paddle-Signature", required = false) String signature,
            @RequestBody(required = false) byte[] body) {
        byte[] raw = body == null ? new byte[0] : body;
        Instant now = clock.instant();
        if (!paddle.webhooksEnabled()) {
            log.error("Paddle webhook refused: PADDLE_WEBHOOK_SECRET is not configured");
            throw new ApiException(ErrorCode.PAYMENTS_UNAVAILABLE, "Webhooks are not configured.");
        }
        if (!PaddleSignatureUtils.valid(signature, raw, paddle.webhookSecret(), now, paddle.signatureTolerance())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Invalid signature.");
        }
        JsonNode event;
        try {
            event = json.readTree(raw);
        } catch (JacksonException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Body is not JSON.");
        }
        String eventId = event.path("event_id").asString("");
        String eventType = event.path("event_type").asString("");
        if (eventId.isBlank() || eventType.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "event_id and event_type are required.");
        }
        boolean fresh =
                events.insertIfAbsent(PROVIDER, eventId, eventType, new String(raw, StandardCharsets.UTF_8), now);
        if (!fresh && events.isProcessed(PROVIDER, eventId)) {
            return ResponseEntity.ok().build();
        }
        processor.process(eventId, eventType, event);
        return ResponseEntity.ok().build();
    }
}
