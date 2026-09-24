package com.insidejoke.billing;

import com.insidejoke.billing.dto.WebhookOutcomeDto;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Processes a stored Paddle event and records the outcome; used by the webhook and by the admin "replay" button. */
@Service
public class PaddleEventService {

    public static final String PROVIDER = "PADDLE";

    private static final Logger log = LoggerFactory.getLogger(PaddleEventService.class);

    private final WebhookEventRepository events;
    private final PaymentService payments;
    private final Clock clock;

    public PaddleEventService(WebhookEventRepository events, PaymentService payments, Clock clock) {
        this.events = events;
        this.payments = payments;
        this.clock = clock;
    }

    public WebhookOutcomeDto process(String eventId, String eventType, JsonNode event) {
        try {
            PaymentService.Handled handled = payments.handle(eventType, event.path("data"));
            events.markProcessed(
                    PROVIDER, eventId, clock.instant(), handled.outcome(), handled.reason(), handled.detail());
            log.info(
                    "Paddle {} {}: {}{}",
                    eventType,
                    eventId,
                    handled.result(),
                    handled.reason() == null ? "" : " (" + handled.reason() + ")");
            return new WebhookOutcomeDto(true, handled.result().name());
        } catch (PaymentService.UnprocessableEventException e) {
            events.markFailed(PROVIDER, eventId, e.getMessage());
            log.error("Paddle {} {} needs manual attention: {}", eventType, eventId, e.getMessage());
            return new WebhookOutcomeDto(false, e.getMessage());
        }
    }
}
