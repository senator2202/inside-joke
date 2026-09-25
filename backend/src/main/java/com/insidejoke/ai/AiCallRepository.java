package com.insidejoke.ai;

import com.insidejoke.common.DbUtils;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AiCallRepository {

    private final JdbcClient jdbc;

    public AiCallRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AiCallEntity r, Instant now) {
        String error = r.error() == null || r.error().length() <= 500
                ? r.error()
                : r.error().substring(0, 500);
        jdbc.sql(
                        "INSERT INTO ai_call (game_session_id, purpose, provider, model, prompt_version, input_tokens, output_tokens, "
                                + "tts_chars, cost_micros, latency_ms, outcome, is_free, error, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .param(r.gameSessionId())
                .param(r.purpose().name())
                .param(r.provider())
                .param(r.model())
                .param(r.promptVersion())
                .param(r.inputTokens())
                .param(r.outputTokens())
                .param(r.ttsChars())
                .param(r.costMicros())
                .param(r.latencyMs())
                .param(r.outcome().name())
                .param(r.freeGame())
                .param(error)
                .param(DbUtils.ts(now))
                .update();
    }

    /** Spend of free games since the given instant, in micro-dollars. */
    public long freeGameCostSince(Instant since) {
        return jdbc.sql("SELECT COALESCE(sum(cost_micros), 0) FROM ai_call WHERE is_free AND created_at >= ?")
                .param(DbUtils.ts(since))
                .query(Long.class)
                .single();
    }

    public long costForGame(UUID gameSessionId) {
        return jdbc.sql("SELECT COALESCE(sum(cost_micros), 0) FROM ai_call WHERE game_session_id = ?")
                .param(gameSessionId)
                .query(Long.class)
                .single();
    }

    // ---------------------------------------------------------------- reporting

    /** Cost and calls in [from, to); failures are every outcome but OK. */
    public record AiTotals(long costMicros, long freeGameCostMicros, int calls, int failures) {}

    public AiTotals totals(Instant from, Instant to) {
        return jdbc.sql(
                        "SELECT coalesce(sum(cost_micros), 0), coalesce(sum(cost_micros) FILTER (WHERE is_free), 0), count(*), "
                                + "count(*) FILTER (WHERE outcome <> " + DbUtils.sql(AiOutcome.OK)
                                + ") FROM ai_call WHERE created_at >= ? AND created_at < ?")
                .params(DbUtils.ts(from), DbUtils.ts(to))
                .query((rs, n) -> new AiTotals(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getInt(4)))
                .single();
    }

    public Map<String, Long> costByPurpose(Instant from, Instant to) {
        Map<String, Long> byPurpose = new LinkedHashMap<>();
        jdbc.sql("SELECT purpose, sum(cost_micros) FROM ai_call WHERE created_at >= ? AND created_at < "
                        + "? GROUP BY purpose ORDER BY 2 DESC")
                .params(DbUtils.ts(from), DbUtils.ts(to))
                .query((rs, n) -> byPurpose.put(rs.getString(1), rs.getLong(2)))
                .list();
        return byPurpose;
    }

    public Map<LocalDate, Long> costPerDay(Instant from, Instant to) {
        Map<LocalDate, Long> cost = new LinkedHashMap<>();
        jdbc.sql("SELECT (created_at AT TIME ZONE 'UTC')::date, sum(cost_micros) FROM ai_call WHERE "
                        + "created_at >= ? AND created_at < ? "
                        + "GROUP BY 1")
                .params(DbUtils.ts(from), DbUtils.ts(to))
                .query((rs, n) -> cost.put(rs.getObject(1, LocalDate.class), rs.getLong(2)))
                .list();
        return cost;
    }

    // ---------------------------------------------------------------- call log

    /** One recorded call, with the room it was made for. */
    public record LoggedCall(
            long id,
            Instant createdAt,
            String purpose,
            String provider,
            String model,
            String promptVersion,
            String outcome,
            Integer inputTokens,
            Integer outputTokens,
            Integer ttsChars,
            long costMicros,
            int latencyMs,
            boolean freeGame,
            UUID gameSessionId,
            String roomCode,
            String error) {}

    /**
     * Filters for the call log; null means any. {@code failuresOnly} selects ERROR, TIMEOUT and INVALID_JSON and wins
     * over {@code outcome}.
     */
    public record Search(Instant from, Instant to, String purpose, String outcome, boolean failuresOnly) {}

    private static final String FAILED = DbUtils.sqlList(AiOutcome.FAILURES);

    /** The newest call to Anthropic: any real call, or only a failed one. Budget fallbacks never reached Anthropic. */
    public Optional<LoggedCall> latestAnthropic(boolean failuresOnly) {
        return jdbc.sql("SELECT c.*, g.room_code FROM ai_call c LEFT JOIN game_session g ON g.id = c.game_session_id "
                        + "WHERE c.provider = '" + AiProvider.ANTHROPIC.wire() + "' AND "
                        + (failuresOnly
                                ? "c.outcome IN " + FAILED
                                : "c.outcome <> " + DbUtils.sql(AiOutcome.FALLBACK) + "")
                        + " ORDER BY c.created_at DESC, c.id DESC LIMIT 1")
                .query(AiCallRepository::logged)
                .optional();
    }

    public long count(Search search) {
        List<Object> params = new ArrayList<>();
        return jdbc.sql("SELECT count(*) FROM ai_call c" + where(search, params))
                .params(params)
                .query(Long.class)
                .single();
    }

    /** Newest first. */
    public List<LoggedCall> search(Search search, int limit, long offset) {
        List<Object> params = new ArrayList<>();
        String where = where(search, params);
        params.add(limit);
        params.add(offset);
        return jdbc.sql("SELECT c.*, g.room_code FROM ai_call c LEFT JOIN game_session g ON g.id = c.game_session_id"
                        + where + " ORDER BY c.created_at DESC, c.id DESC LIMIT ? OFFSET ?")
                .params(params)
                .query(AiCallRepository::logged)
                .list();
    }

    private static String where(Search s, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        if (s.from() != null) {
            where.append(" AND c.created_at >= ?");
            params.add(DbUtils.ts(s.from()));
        }
        if (s.to() != null) {
            where.append(" AND c.created_at < ?");
            params.add(DbUtils.ts(s.to()));
        }
        if (s.purpose() != null) {
            where.append(" AND c.purpose = ?");
            params.add(s.purpose());
        }
        if (s.failuresOnly()) {
            where.append(" AND c.outcome IN ").append(FAILED);
        } else if (s.outcome() != null) {
            where.append(" AND c.outcome = ?");
            params.add(s.outcome());
        }
        return where.toString();
    }

    private static LoggedCall logged(ResultSet rs, int row) throws SQLException {
        return new LoggedCall(
                rs.getLong("id"),
                DbUtils.instant(rs, "created_at"),
                rs.getString("purpose"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getString("outcome"),
                DbUtils.integer(rs, "input_tokens"),
                DbUtils.integer(rs, "output_tokens"),
                DbUtils.integer(rs, "tts_chars"),
                rs.getLong("cost_micros"),
                rs.getInt("latency_ms"),
                rs.getBoolean("is_free"),
                DbUtils.uuid(rs, "game_session_id"),
                rs.getString("room_code"),
                rs.getString("error"));
    }
}
