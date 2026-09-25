package com.insidejoke.moderation;

import com.insidejoke.common.DbUtils;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Counts of blocked and skipped content by category. The text itself is never stored. */
@Repository
public class ModerationEventRepository {

    private final JdbcClient jdbc;

    public ModerationEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            @Nullable UUID gameSessionId,
            ModerationStage stage,
            String category,
            ModerationAction action,
            Instant now) {
        jdbc.sql(
                        "INSERT INTO moderation_event (game_session_id, stage, category, action, created_at) VALUES (?, ?, ?, ?, ?)")
                .param(gameSessionId)
                .param(stage.name())
                .param(category)
                .param(action.name())
                .param(DbUtils.ts(now))
                .update();
    }
}
