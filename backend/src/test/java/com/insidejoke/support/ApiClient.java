package com.insidejoke.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP client with a cookie jar and automatic CSRF header, like the SPA in a browser. */
public final class ApiClient {

    public record Resp(int status, String body, HttpHeaders headers, JsonMapper mapper) {

        public JsonNode json() {
            return mapper.readTree(body);
        }

        public String errorCode() {
            return json().path("error").path("code").asString();
        }

        public Optional<String> location() {
            return headers.firstValue("Location");
        }
    }

    private final HttpClient http;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final String base;
    private final JsonMapper json;

    public ApiClient(int port, JsonMapper json) {
        this.base = "http://localhost:" + port;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public String base() {
        return base;
    }

    public Resp get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    public Resp post(String path, Object body) {
        return send(withCsrf(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : json.writeValueAsString(body)))));
    }

    public Resp postWithoutCsrf(String path, Object body) {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    }

    /** Posts exact bytes with extra headers (signed webhooks). */
    public Resp postRaw(String path, byte[] body, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return send(b);
    }

    public Resp put(String path, Object body) {
        return send(withCsrf(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))));
    }

    public Resp delete(String path) {
        return send(withCsrf(HttpRequest.newBuilder(URI.create(base + path)).DELETE()));
    }

    public String cookie(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("UastIncorrectHttpHeaderInspection") // Spring Security's CSRF header
    private HttpRequest.Builder withCsrf(HttpRequest.Builder builder) {
        String token = cookie("XSRF-TOKEN");
        if (token == null) {
            get("/api/auth/csrf");
            token = cookie("XSRF-TOKEN");
        }
        return builder.header("X-XSRF-TOKEN", token);
    }

    private Resp send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> r = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body(), r.headers(), json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
