package com.insidejoke.room;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.game.GameEngineService;
import com.insidejoke.game.ReplyHandler;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The /ws endpoint (blueprint 9). The first message must be {@code hello} with a room token, within 5 seconds.
 * Envelope: {"v":1,"type":"...","reqId":"...","data":{...}}. Server replies: state, ok, error, kicked, closed, pong.
 * A {@code reqId} must not repeat for the same room token: a request re-sent with the same reqId (after a reconnect)
 * is not run again and gets the first answer.
 */
@Component
public class GameSocketHandler extends TextWebSocketHandler {

    static final int MAX_MESSAGE_BYTES = 4096;
    static final long HELLO_TIMEOUT_MS = 5000;
    static final CloseStatus NO_HELLO = new CloseStatus(4401, "hello expected");
    static final CloseStatus UNKNOWN_ROOM = new CloseStatus(4404, "room not found");

    private final GameEngineService engine;
    private final BroadcastService broadcaster;
    private final JsonMapper json;
    private final ExecutorService io;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ConnectionHandler> connections = new ConcurrentHashMap<>();

    public GameSocketHandler(
            GameEngineService engine,
            BroadcastService broadcaster,
            JsonMapper json,
            ExecutorService ioExecutor,
            ScheduledExecutorService gameScheduler) {
        this.engine = engine;
        this.broadcaster = broadcaster;
        this.json = json;
        this.io = ioExecutor;
        this.scheduler = gameScheduler;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 5000, 512 * 1024);
        ConnectionHandler c = new ConnectionHandler(safe, io);
        connections.put(session.getId(), c);
        scheduler.schedule(
                () -> {
                    if (!c.attached() && !c.closed) {
                        c.close(NO_HELLO);
                    }
                },
                HELLO_TIMEOUT_MS,
                TimeUnit.MILLISECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ConnectionHandler c = connections.get(session.getId());
        if (c == null || c.closed) {
            return;
        }
        if (message.getPayload().getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            error(c, reqIdOf(message.getPayload()), new ApiException(ErrorCode.BAD_MESSAGE, "Message too large."));
            return;
        }
        if (!c.bucket.tryConsume(1)) {
            error(c, reqIdOf(message.getPayload()), new ApiException(ErrorCode.RATE_LIMITED));
            return;
        }
        JsonNode envelope;
        try {
            envelope = json.readTree(message.getPayload());
        } catch (JacksonException e) {
            error(c, null, new ApiException(ErrorCode.BAD_MESSAGE, "Not JSON."));
            return;
        }
        String type = envelope.path("type").asString("");
        String reqId =
                envelope.path("reqId").isString() ? envelope.path("reqId").asString() : null;
        if (envelope.path("v").asInt(0) != 1 || type.isEmpty()) {
            error(c, reqId, new ApiException(ErrorCode.BAD_MESSAGE, "Expected {v:1, type, reqId, data}."));
            return;
        }
        JsonNode data = envelope.path("data");
        if ("ping".equals(type)) {
            c.send(json.writeValueAsString(frame("pong", reqId, Map.of())));
            return;
        }
        if (!c.attached()) {
            if (!"hello".equals(type)) {
                error(c, reqId, new ApiException(ErrorCode.BAD_MESSAGE, "Say hello first."));
                c.close(NO_HELLO);
                return;
            }
            hello(c, reqId, data.path("token").asString(""));
            return;
        }
        if ("hello".equals(type)) {
            error(c, reqId, new ApiException(ErrorCode.BAD_MESSAGE, "Already connected."));
            return;
        }
        engine.command(c.room, c.member, reqId, type, data, reply(c, reqId));
    }

    private void hello(ConnectionHandler c, String reqId, String token) {
        GameEngineService.Resolved resolved = engine.resolve(token).orElse(null);
        if (resolved == null) {
            error(c, reqId, new ApiException(ErrorCode.ROOM_NOT_FOUND, "This room no longer exists."));
            c.close(UNKNOWN_ROOM);
            return;
        }
        c.room = resolved.room();
        c.member = resolved.member();
        broadcaster.register(c);
        engine.connected(c.room, c.member);
        c.send(json.writeValueAsString(
                frame("ok", reqId, Map.of("role", c.member.kind().name()))));
        broadcaster.sendNow(c);
    }

    /** Best effort: lets the client match an error to its request even when the message itself is rejected. */
    private String reqIdOf(String payload) {
        try {
            JsonNode reqId = json.readTree(payload).path("reqId");
            return reqId.isString() ? reqId.asString() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ConnectionHandler c = connections.remove(session.getId());
        if (c == null) {
            return;
        }
        c.closed = true;
        if (c.attached()) {
            broadcaster.unregister(c);
            engine.disconnected(c.room, c.member);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        ConnectionHandler c = connections.get(session.getId());
        if (c != null) {
            c.close(CloseStatus.SERVER_ERROR);
        }
    }

    private ReplyHandler reply(ConnectionHandler c, String reqId) {
        return new ReplyHandler() {
            @Override
            public void ok(Map<String, Object> data) {
                c.send(json.writeValueAsString(frame("ok", reqId, data)));
            }

            @Override
            public void error(ApiException e) {
                GameSocketHandler.this.error(c, reqId, e);
            }
        };
    }

    private void error(ConnectionHandler c, String reqId, ApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code().name());
        body.put("message", e.getMessage());
        if (!e.details().isEmpty()) {
            body.put("details", e.details());
        }
        c.send(json.writeValueAsString(frame("error", reqId, body)));
    }

    private static Map<String, Object> frame(String type, String reqId, Object data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v", 1);
        out.put("type", type);
        if (reqId != null) {
            out.put("reqId", reqId);
        }
        out.put("data", data);
        return out;
    }

    int openConnections() {
        return connections.size();
    }
}
