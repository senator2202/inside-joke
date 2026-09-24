package com.insidejoke.room;

import com.insidejoke.game.Member;
import com.insidejoke.game.RoomState;
import com.insidejoke.web.RateLimitService;
import io.github.bucket4j.Bucket;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * One WebSocket. Outgoing frames go through a per-connection queue drained on a virtual thread, so the engine
 * and broadcaster never block on a slow phone, and frames keep their order.
 */
final class ConnectionHandler {

    static final RateLimitService.Limit MESSAGES = new RateLimitService.Limit("ws-msg", 10, Duration.ofSeconds(1));
    /**
     * Most unsent output one connection may hold (characters). A client that stops reading (a slow or malicious socket,
     * a phone asleep on a half-open connection) is disconnected instead of piling snapshots up in memory; it reconnects
     * and receives one fresh snapshot, since every state frame is complete.
     */
    static final int MAX_QUEUED_CHARS = 1 << 20;

    static final CloseStatus TOO_SLOW = new CloseStatus(4408, "Too slow");
    private static final Logger log = LoggerFactory.getLogger(ConnectionHandler.class);

    final WebSocketSession session;
    final Bucket bucket = RateLimitService.newBucket(MESSAGES);
    private final Executor io;
    private final ConcurrentLinkedQueue<Object> outbox = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicLong queuedChars = new AtomicLong();
    volatile RoomState room;
    volatile Member member;
    volatile boolean closed;

    ConnectionHandler(WebSocketSession session, Executor io) {
        this.session = session;
        this.io = io;
    }

    boolean attached() {
        return member != null;
    }

    void send(String payload) {
        if (closed) {
            return;
        }
        if (queuedChars.addAndGet(payload.length()) > MAX_QUEUED_CHARS) {
            overflow();
            return;
        }
        outbox.add(payload);
        drain();
    }

    long queuedChars() {
        return queuedChars.get();
    }

    private void overflow() {
        if (closed) {
            return;
        }
        closed = true;
        outbox.clear();
        queuedChars.set(0);
        log.info("Closing connection {}: it stopped reading", session.getId());
        io.execute(() -> {
            try {
                session.close(TOO_SLOW);
            } catch (IOException | IllegalStateException e) {
                log.debug("Close of slow connection {} failed: {}", session.getId(), e.getMessage());
            }
        });
    }

    /** Sends whatever is queued, then closes. */
    void close(CloseStatus status) {
        outbox.add(status);
        drain();
    }

    private void drain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        io.execute(() -> {
            try {
                Object next;
                while ((next = outbox.poll()) != null) {
                    if (closed) {
                        continue;
                    }
                    if (next instanceof CloseStatus status) {
                        closed = true;
                        session.close(status);
                    } else {
                        String text = (String) next;
                        queuedChars.addAndGet(-text.length());
                        if (session.isOpen()) {
                            session.sendMessage(new TextMessage(text));
                        }
                    }
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping connection {}: {}", session.getId(), e.getMessage());
                closed = true;
                outbox.clear();
                queuedChars.set(0);
            } finally {
                draining.set(false);
                if (!outbox.isEmpty() && !closed) {
                    drain();
                }
            }
        });
    }
}
