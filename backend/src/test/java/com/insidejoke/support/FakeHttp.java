package com.insidejoke.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Local HTTP server that stands in for external APIs (Anthropic, TTS, Paddle, Resend, Google, PostHog).
 * Tests program replies per "METHOD /path" and inspect recorded requests.
 */
public final class FakeHttp {

    public record Recorded(String method, String path, String query, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null
                        && e.getKey().equalsIgnoreCase(name)
                        && !e.getValue().isEmpty()) {
                    return e.getValue().getFirst();
                }
            }
            return null;
        }
    }

    public record Reply(int status, String contentType, byte[] body, long delayMs, Map<String, String> headers) {

        public static Reply json(int status, String body) {
            return new Reply(status, "application/json", body.getBytes(StandardCharsets.UTF_8), 0, Map.of());
        }

        public static Reply bytes(int status, String contentType, byte[] body) {
            return new Reply(status, contentType, body, 0, Map.of());
        }

        public Reply delayed(long millis) {
            return new Reply(status, contentType, body, millis, headers);
        }

        /** The same reply with extra response headers (for example, rate-limit headers). */
        public Reply withHeaders(Map<String, String> extra) {
            return new Reply(status, contentType, body, delayMs, extra);
        }
    }

    public static final FakeHttp INSTANCE = new FakeHttp();

    private final HttpServer server;
    private final Map<String, Function<Recorded, Reply>> routes = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    private FakeHttp() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void on(String method, String path, Function<Recorded, Reply> handler) {
        routes.put(method + " " + path, handler);
    }

    public List<Recorded> requests(String method, String path) {
        return requests.stream()
                .filter(r -> r.method().equals(method) && r.path().equals(path))
                .toList();
    }

    public void reset() {
        routes.clear();
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Recorded rec = new Recorded(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                Map.copyOf(exchange.getRequestHeaders()),
                body);
        requests.add(rec);
        Function<Recorded, Reply> route = routes.get(rec.method() + " " + rec.path());
        if (route == null) {
            route = r -> Reply.json(404, "{\"error\":\"no fake route for " + r.method() + " " + r.path() + "\"}");
        }
        Reply reply = route.apply(rec);
        if (reply.delayMs() > 0) {
            try {
                Thread.sleep(reply.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        exchange.getResponseHeaders().add("Content-Type", reply.contentType());
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
        try (var out = exchange.getResponseBody()) {
            out.write(reply.body());
        }
    }
}
