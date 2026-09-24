package com.insidejoke.mail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Resend HTTP API client: POST /emails. */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "resend", matchIfMissing = true)
public class ResendMailClient implements MailClient {

    private final HttpClient http;
    private final JsonMapper json;
    private final MailProperties props;

    public ResendMailClient(HttpClient http, JsonMapper json, MailProperties props) {
        this.http = http;
        this.json = json;
        this.props = props;
    }

    @Override
    public void send(EmailMessage message) {
        String apiKey = props.resend().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new MailSendException("RESEND_API_KEY is not configured", null);
        }
        Map<String, Object> body = Map.of(
                "from", props.from(),
                "to", List.of(message.to()),
                "subject", message.subject(),
                "text", message.text(),
                "html", message.html());
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.resend().baseUrl() + "/emails"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new MailSendException("Resend responded with HTTP " + response.statusCode(), null);
            }
        } catch (IOException e) {
            throw new MailSendException("Resend request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailSendException("Interrupted while sending email", e);
        }
    }
}
