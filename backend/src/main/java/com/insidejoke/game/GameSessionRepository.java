package com.insidejoke.game;

import com.insidejoke.common.DbUtils;
import com.insidejoke.common.Language;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Game summaries. Never stores names, answers, secrets or generated text. */
@Repository
public class GameSessionRepository {

    /** Values known when a game starts. */
    public record Start(
            UUID hostUserId,
            UUID previousSessionId,
            String roomCode,
            RoomMode mode,
            Tone tone,
            GameLength length,
            String promptVersion,
            boolean free,
            UUID entitlementId,
            int playerCount,
            Instant startedAt,
            Language language) {}

    /** Totals written when the game ends. */
    public record Finish(
            int playerCount, int audiencePeak, int roundsPlayed, int dossierFacts, EndReason reason, Instant endedAt) {}

    private final JdbcClient jdbc;

    public GameSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(Start s) {
        return jdbc.sql(
                        "INSERT INTO game_session (host_user_id, previous_session_id, room_code, mode, tone, length, prompt_version, "
                                + "is_free, entitlement_id, player_count, started_at, language) VALUES (?, ?, "
                                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")
                .params(
                        s.hostUserId(),
                        s.previousSessionId(),
                        s.roomCode(),
                        s.mode().name(),
                        s.tone().name(),
                        s.length().name(),
                        s.promptVersion(),
                        s.free(),
                        s.entitlementId(),
                        s.playerCount(),
                        DbUtils.ts(s.startedAt()),
                        (s.language() == null ? Language.EN : s.language()).code())
                .query(UUID.class)
                .single();
    }

    /** Completes a game once; later calls (for example a close after the finale) keep the first result. */
    public boolean finish(UUID id, Finish f) {
        return jdbc.sql(
                                "UPDATE game_session SET player_count = ?, audience_peak = ?, rounds_played = ?, dossier_facts = ?, "
                                        + "end_reason = ?, ended_at = ? WHERE id = ? AND ended_at IS NULL")
                        .params(
                                f.playerCount(),
                                f.audiencePeak(),
                                f.roundsPlayed(),
                                f.dossierFacts(),
                                f.reason().name(),
                                DbUtils.ts(f.endedAt()),
                                id)
                        .update()
                == 1;
    }

    public int countFreeSince(UUID hostUserId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM game_session WHERE host_user_id = ? AND is_free AND started_at >= ?")
                .params(hostUserId, DbUtils.ts(since))
                .query(Integer.class)
                .single();
    }

    /** Earliest free game in the window, which decides when the next free game becomes available. */
    public Optional<Instant> oldestFreeSince(UUID hostUserId, Instant since) {
        return jdbc.sql(
                        "SELECT min(started_at) FROM game_session WHERE host_user_id = ? AND is_free AND started_at >= ?")
                .params(hostUserId, DbUtils.ts(since))
                .query((rs, n) -> DbUtils.instant(rs, "min"))
                .optional();
    }

    public int countForEntitlementSince(UUID entitlementId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM game_session WHERE entitlement_id = ? AND started_at >= ?")
                .params(entitlementId, DbUtils.ts(since))
                .query(Integer.class)
                .single();
    }

    public Optional<UUID> findHost(UUID sessionId) {
        return jdbc.sql("SELECT host_user_id FROM game_session WHERE id = ?")
                .param(sessionId)
                .query(UUID.class)
                .optional();
    }

    // ---------------------------------------------------------------- reporting

    /** Games started in [from, to). */
    public record GameTotals(int total, int free, int paid, int completed, double avgPlayers) {}

    /** Games and paid games started on one UTC day. */
    public record DayCount(LocalDate day, int games, int paidGames) {}

    public GameTotals totals(Instant from, Instant to) {
        return jdbc.sql("SELECT count(*), count(*) FILTER (WHERE is_free), count(*) FILTER (WHERE NOT is_free), "
                        + "count(*) FILTER (WHERE end_reason = " + DbUtils.sql(EndReason.COMPLETED)
                        + "), coalesce(avg(player_count), 0) "
                        + "FROM game_session WHERE started_at >= ? AND started_at < ?")
                .params(DbUtils.ts(from), DbUtils.ts(to))
                .query((rs, n) -> new GameTotals(
                        rs.getInt(1),
                        rs.getInt(2),
                        rs.getInt(3),
                        rs.getInt(4),
                        Math.round(rs.getDouble(5) * 10) / 10.0))
                .single();
    }

    /** How games started in [from, to) ended, most common first; games still running count as IN_PROGRESS. */
    public Map<String, Integer> endReasons(Instant from, Instant to) {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        jdbc.sql(
                        "SELECT coalesce(end_reason, 'IN_PROGRESS'), count(*) FROM game_session WHERE started_at >= ? AND started_at < ? "
                                + "GROUP BY coalesce(end_reason, 'IN_PROGRESS') ORDER BY count(*) DESC")
                .params(DbUtils.ts(from), DbUtils.ts(to))
                .query((rs, n) -> reasons.put(rs.getString(1), rs.getInt(2)))
                .list();
        return reasons;
    }

    public int activeHosts(Instant from, Instant to) {
        return jdbc.sql(
                        "SELECT count(DISTINCT host_user_id) FROM game_session WHERE started_at >= ? AND started_at < ?")
                .params(DbUtils.ts(from), DbUtils.ts(to))
                .query(Integer.class)
                .single();
    }

    public List<DayCount> perDay(Instant from, Instant to) {
        return jdbc.sql(
                        "SELECT (started_at AT TIME ZONE 'UTC')::date, count(*), count(*) FILTER (WHERE NOT is_free) FROM game_session "
                                + "WHERE started_at >= ? AND started_at < ? GROUP BY (started_at AT TIME ZONE 'UTC')::date")
                .params(DbUtils.ts(from), DbUtils.ts(to))
                .query((rs, n) -> new DayCount(rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getInt(3)))
                .list();
    }

    public int countByHost(UUID hostUserId) {
        return jdbc.sql("SELECT count(*) FROM game_session WHERE host_user_id = ?")
                .param(hostUserId)
                .query(Integer.class)
                .single();
    }
}
