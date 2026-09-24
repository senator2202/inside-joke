package com.insidejoke.game;

import com.insidejoke.auth.AccountDeletedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Closes the rooms of a deleted account once the deletion is committed. */
@Component
public class RoomClosingListener {

    private final GameEngineService engine;

    public RoomClosingListener(GameEngineService engine) {
        this.engine = engine;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void accountDeleted(AccountDeletedEvent event) {
        engine.closeRoomsOf(event.userId());
    }
}
