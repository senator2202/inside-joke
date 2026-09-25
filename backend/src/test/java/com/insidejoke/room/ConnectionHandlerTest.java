package com.insidejoke.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insidejoke.support.Await;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

class ConnectionHandlerTest {

    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stop() {
        io.shutdownNow();
    }

    private static WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("s1");
        return session;
    }

    @Test
    void aClientThatStopsReadingIsDisconnectedInsteadOfFillingMemory() throws Exception {
        WebSocketSession session = session();
        CountDownLatch stuck = new CountDownLatch(1);
        doAnswer(inv -> {
                    stuck.await();
                    return null;
                })
                .when(session)
                .sendMessage(any());
        ConnectionHandler c = new ConnectionHandler(session, io);

        String snapshot = "x".repeat(4_000);
        for (int i = 0; i < 1_000; i++) {
            c.send(snapshot);
            assertThat(c.queuedChars()).isLessThanOrEqualTo(ConnectionHandler.MAX_QUEUED_CHARS);
        }

        verify(session, timeout(2_000)).close(ConnectionHandler.TOO_SLOW);
        assertThat(c.queuedChars()).isZero();
        c.send(snapshot);
        assertThat(c.queuedChars()).as("nothing is queued after the disconnect").isZero();
        stuck.countDown();
    }

    @Test
    void aReadingClientGetsEveryFrameInOrder() throws Exception {
        WebSocketSession session = session();
        List<String> received = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
                    WebSocketMessage<?> m = inv.getArgument(0);
                    received.add(((TextMessage) m).getPayload());
                    return null;
                })
                .when(session)
                .sendMessage(any());
        ConnectionHandler c = new ConnectionHandler(session, io);
        for (int i = 0; i < 500; i++) {
            c.send("frame-" + i);
        }
        Await.until("all frames sent", Duration.ofSeconds(2), () -> received.size() == 500);
        assertThat(received).hasSize(500);
        assertThat(received.getFirst()).isEqualTo("frame-0");
        assertThat(received.get(499)).isEqualTo("frame-499");
        assertThat(c.queuedChars()).isZero();
    }
}
