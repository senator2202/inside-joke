package com.insidejoke.game;

import com.insidejoke.common.DbUtils;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FeedbackRepository {

    private final JdbcClient jdbc;

    public FeedbackRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One rating per game; submitting again replaces it. */
    public void upsert(UUID sessionId, int rating, String comment, Instant now) {
        jdbc.sql("INSERT INTO game_feedback (game_session_id, rating, comment, created_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (game_session_id) DO UPDATE SET rating = EXCLUDED.rating, comment = EXCLUDED.comment, "
                        + "created_at = EXCLUDED.created_at")
                .params(sessionId, rating, comment, DbUtils.ts(now))
                .update();
    }
}
