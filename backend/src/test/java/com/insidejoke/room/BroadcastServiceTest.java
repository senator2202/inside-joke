package com.insidejoke.room;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insidejoke.game.Member;
import com.insidejoke.game.MemberKind;
import com.insidejoke.game.Phase;
import com.insidejoke.game.RoomProjectionService;
import com.insidejoke.game.RoomState;
import com.insidejoke.game.dto.RoomStateDto;
import com.insidejoke.support.ManualClock;
import com.insidejoke.support.ManualScheduler;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/** Snapshot fan-out (blueprint 4.5): what is built per connection and what is shared. */
class BroadcastServiceTest {

    private final ManualClock clock = new ManualClock();
    private final ManualScheduler scheduler = new ManualScheduler(clock);
    private final RoomProjectionService projector = mock(RoomProjectionService.class);
    private final BroadcastService broadcaster = new BroadcastService(
            scheduler,
            JsonMapper.builder().build(),
            new StaticListableBeanFactory(Map.of("projector", projector)).getBeanProvider(RoomProjectionService.class));
    private final RoomState room = mock(RoomState.class);

    BroadcastServiceTest() {
        when(room.getCode()).thenReturn("KWMP");
        when(room.call(any()))
                .thenAnswer(
                        call -> call.<Function<RoomState, Object>>getArgument(0).apply(room));
        when(projector.project(eq(room), any())).thenReturn(snapshot());
    }

    private static RoomStateDto snapshot() {
        return new RoomStateDto(
                1,
                0,
                null,
                Phase.LOBBY,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null);
    }

    private WebSocketSession connect(MemberKind kind, String token) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(token);
        ConnectionHandler c = new ConnectionHandler(session, Runnable::run);
        c.room = room;
        c.member = new Member(token, kind, kind == MemberKind.PLAYER ? "p-" + token : null, null);
        broadcaster.register(c);
        return session;
    }

    private void flush() {
        broadcaster.changed(room);
        clock.advance(Duration.ofMillis(BroadcastService.COALESCE_MS));
        scheduler.runDue();
    }

    @Test
    void allScreenCopiesShareOneSnapshotWhilePlayersGetTheirOwn() throws Exception {
        List<WebSocketSession> copies = List.of(
                connect(MemberKind.SCREEN, "c1"), connect(MemberKind.SCREEN, "c2"), connect(MemberKind.SCREEN, "c3"));
        WebSocketSession player = connect(MemberKind.PLAYER, "p1");

        flush();

        verify(projector, times(1)).project(eq(room), argThat(m -> m.kind() == MemberKind.SCREEN));
        verify(projector, times(1)).project(eq(room), argThat(m -> m.kind() == MemberKind.PLAYER));
        for (WebSocketSession copy : copies) {
            verify(copy).sendMessage(any(TextMessage.class));
        }
        verify(player).sendMessage(any(TextMessage.class));
    }
}
