package com.insidejoke.room;

import com.insidejoke.game.MemberKind;
import com.insidejoke.game.RoomEventListener;
import com.insidejoke.game.RoomProjectionService;
import com.insidejoke.game.RoomState;
import com.insidejoke.game.dto.RoomStateDto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends full state snapshots (blueprint 4.5). Changes within 50 ms are coalesced into one snapshot; stream
 * viewers get at most one per second. Screen copies all get one shared snapshot, and viewers one per voting state.
 * Snapshots are built under the room lock and sent after it is released.
 */
@Service
public class BroadcastService implements RoomEventListener {

    static final long COALESCE_MS = 50;
    static final long AUDIENCE_INTERVAL_MS = 1000;
    static final CloseStatus KICKED = new CloseStatus(4403, "kicked");
    static final CloseStatus CLOSED = new CloseStatus(4410, "room closed");

    private static final class BroadcastState {
        final Set<ConnectionHandler> connections = ConcurrentHashMap.newKeySet();
        final AtomicBoolean scheduled = new AtomicBoolean();
        volatile long lastAudienceMs = Long.MIN_VALUE / 2;
        final AtomicBoolean audienceScheduled = new AtomicBoolean();
    }

    private final Map<String, BroadcastState> rooms = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final JsonMapper json;
    /** Resolved lazily: the projector depends on the engine, which depends on this class. */
    private final ObjectProvider<RoomProjectionService> projectors;

    public BroadcastService(
            ScheduledExecutorService gameScheduler, JsonMapper json, ObjectProvider<RoomProjectionService> projectors) {
        this.scheduler = gameScheduler;
        this.json = json;
        this.projectors = projectors;
    }

    private RoomProjectionService projector() {
        return projectors.getObject();
    }

    void register(ConnectionHandler c) {
        rooms.computeIfAbsent(c.room.getCode(), k -> new BroadcastState())
                .connections
                .add(c);
    }

    void unregister(ConnectionHandler c) {
        BroadcastState state = rooms.get(c.room.getCode());
        if (state != null) {
            state.connections.remove(c);
        }
    }

    /** Sends the current snapshot to one connection right away (after hello). */
    void sendNow(ConnectionHandler c) {
        String payload = c.room.call(r -> envelope(projector().project(r, c.member)));
        c.send(payload);
    }

    @Override
    public void changed(RoomState room) {
        BroadcastState state = rooms.get(room.getCode());
        if (state == null || state.connections.isEmpty()) {
            return;
        }
        if (state.scheduled.compareAndSet(false, true)) {
            scheduler.schedule(() -> flush(room, state, false), COALESCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flush(RoomState room, BroadcastState state, boolean audienceOnly) {
        if (audienceOnly) {
            state.audienceScheduled.set(false);
        } else {
            state.scheduled.set(false);
        }
        long now = System.nanoTime() / 1_000_000;
        boolean audienceDue = now - state.lastAudienceMs >= AUDIENCE_INTERVAL_MS;
        List<ConnectionHandler> targets = state.connections.stream()
                .filter(c -> c.attached() && (c.member.kind() == MemberKind.AUDIENCE ? audienceDue : !audienceOnly))
                .toList();
        boolean audienceSkipped = !audienceDue
                && state.connections.stream().anyMatch(c -> c.member != null && c.member.kind() == MemberKind.AUDIENCE);
        if (audienceDue && targets.stream().anyMatch(c -> c.member.kind() == MemberKind.AUDIENCE)) {
            state.lastAudienceMs = now;
        }
        Map<ConnectionHandler, String> payloads = room.call(r -> {
            Map<String, String> shared = new HashMap<>();
            Map<ConnectionHandler, String> out = new HashMap<>();
            for (ConnectionHandler c : targets) {
                if (c.member.kind() == MemberKind.AUDIENCE) {
                    RoomStateDto view = projector().project(r, c.member);
                    String key =
                            "aud:" + (view.audience() != null && view.audience().voted());
                    out.put(c, shared.computeIfAbsent(key, k -> envelope(view)));
                } else if (c.member.kind() == MemberKind.SCREEN) {
                    // Every screen copy sees the same thing: one snapshot is built for all of them.
                    out.put(
                            c,
                            shared.computeIfAbsent(
                                    "screen", k -> envelope(projector().project(r, c.member))));
                } else {
                    out.put(c, envelope(projector().project(r, c.member)));
                }
            }
            return out;
        });
        payloads.forEach(ConnectionHandler::send);
        if (audienceSkipped && state.audienceScheduled.compareAndSet(false, true)) {
            long wait = Math.max(1, AUDIENCE_INTERVAL_MS - (now - state.lastAudienceMs));
            scheduler.schedule(() -> flush(room, state, true), wait, TimeUnit.MILLISECONDS);
        }
    }

    private String envelope(RoomStateDto view) {
        return json.writeValueAsString(Map.of("v", 1, "type", "state", "data", view));
    }

    @Override
    public void kicked(RoomState room, String playerId) {
        BroadcastState state = rooms.get(room.getCode());
        if (state == null) {
            return;
        }
        String payload = json.writeValueAsString(Map.of("v", 1, "type", "kicked", "data", Map.of()));
        for (ConnectionHandler c : state.connections) {
            if (c.member != null && playerId.equals(c.member.playerId())) {
                c.send(payload);
                c.close(KICKED);
            }
        }
    }

    @Override
    public void closed(RoomState room) {
        BroadcastState state = rooms.remove(room.getCode());
        if (state == null) {
            return;
        }
        String payload = json.writeValueAsString(Map.of("v", 1, "type", "closed", "data", Map.of()));
        for (ConnectionHandler c : state.connections) {
            if (c.member != null && c.member.kind() == MemberKind.OWNER_SCREEN) {
                c.send(envelope(projector().project(room, c.member)));
            }
            c.send(payload);
            c.close(CLOSED);
        }
    }
}
