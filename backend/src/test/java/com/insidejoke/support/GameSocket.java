package com.insidejoke.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A test WebSocket client speaking the game protocol. Keeps every frame for later assertions. */
public final class GameSocket implements AutoCloseable {

    private final JsonMapper json;
    private final List<JsonNode> frames = new CopyOnWriteArrayList<>();
    private final List<String> raw = new CopyOnWriteArrayList<>();
    /** Shared by all sockets: like a real client, a reconnect of the same token never reuses a reqId. */
    private static final AtomicInteger REQ_IDS = new AtomicInteger();

    private final WebSocket socket;
    private volatile Integer closeCode;

    public GameSocket(int port, JsonMapper json, String origin) {
        this.json = json;
        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder partial = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                partial.append(data);
                if (last) {
                    String text = partial.toString();
                    partial.setLength(0);
                    raw.add(text);
                    frames.add(json.readTree(text));
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                closeCode = statusCode;
                return null;
            }
        };
        this.socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header("Origin", origin)
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), listener)
                .join();
    }

    public GameSocket(int port, JsonMapper json) {
        this(port, json, "http://localhost");
    }

    public static GameSocket connect(int port, JsonMapper json, String token) {
        GameSocket s = new GameSocket(port, json);
        String reqId = s.send("hello", Map.of("token", token));
        JsonNode reply = s.reply(reqId);
        if (!"ok".equals(reply.path("type").asString())) {
            throw new AssertionError("hello failed: " + reply);
        }
        s.state(v -> true);
        return s;
    }

    public String send(String type, Object data) {
        return send(type, data, "t-" + REQ_IDS.incrementAndGet());
    }

    /** Sends a request with a chosen reqId, e.g. to re-send one as a client does after reconnecting. */
    public String send(String type, Object data, String reqId) {
        sendRaw(json.writeValueAsString(Map.of("v", 1, "type", type, "reqId", reqId, "data", data)));
        return reqId;
    }

    public void sendRaw(String text) {
        socket.sendText(text, true).join();
    }

    /** The ok/error reply to a request. */
    public JsonNode reply(String reqId) {
        return Await.value(
                "reply to " + reqId,
                () -> frames.stream()
                        .filter(f -> reqId.equals(f.path("reqId").asString(null))
                                && !"state".equals(f.path("type").asString()))
                        .findFirst()
                        .orElse(null));
    }

    /** Sends a request; like a real client, waits a second and retries when the server says RATE_LIMITED. */
    public JsonNode request(String type, Object data) {
        for (int attempt = 0; ; attempt++) {
            JsonNode reply = reply(send(type, data));
            if (attempt < 3
                    && "RATE_LIMITED".equals(reply.path("data").path("code").asString(""))) {
                Await.pause(1100);
                continue;
            }
            return reply;
        }
    }

    /** Sends a request and asserts it succeeded. */
    public JsonNode ok(String type, Object data) {
        JsonNode reply = request(type, data);
        if (!"ok".equals(reply.path("type").asString())) {
            throw new AssertionError(type + " failed: " + reply);
        }
        return reply.path("data");
    }

    /** Sends a request and returns the error code it failed with. */
    public String error(String type, Object data) {
        JsonNode reply = request(type, data);
        if (!"error".equals(reply.path("type").asString())) {
            throw new AssertionError(type + " should have failed: " + reply);
        }
        return reply.path("data").path("code").asString();
    }

    /** Waits for the newest state snapshot to match. */
    public JsonNode state(Predicate<JsonNode> condition) {
        try {
            return Await.value("state matching condition", () -> {
                JsonNode latest = latest();
                return latest != null && condition.test(latest) ? latest : null;
            });
        } catch (AssertionError e) {
            String latest = String.valueOf(latest());
            throw new AssertionError(
                    e.getMessage() + "; latest state: " + latest.substring(0, Math.min(latest.length(), 1500)), e);
        }
    }

    public JsonNode phase(String phase) {
        return state(s -> phase.equals(s.path("phase").asString()));
    }

    /** Every "state" frame received so far, oldest first. */
    public List<JsonNode> states() {
        return frames.stream()
                .filter(f -> "state".equals(f.path("type").asString()))
                .map(f -> f.path("data"))
                .toList();
    }

    public JsonNode latest() {
        for (int i = frames.size() - 1; i >= 0; i--) {
            if ("state".equals(frames.get(i).path("type").asString())) {
                return frames.get(i).path("data");
            }
        }
        return null;
    }

    public boolean received(String type) {
        return frames.stream().anyMatch(f -> type.equals(f.path("type").asString()));
    }

    public List<String> rawFrames() {
        return new ArrayList<>(raw);
    }

    public Integer closeCode() {
        return closeCode;
    }

    @Override
    public void close() {
        if (!socket.isOutputClosed()) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        }
    }

    public void abort() {
        socket.abort();
    }
}
