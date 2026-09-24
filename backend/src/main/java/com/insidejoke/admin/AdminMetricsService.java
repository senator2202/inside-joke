package com.insidejoke.admin;

import com.insidejoke.admin.dto.AdminMetricsDto;
import com.insidejoke.admin.dto.AiMetricsDto;
import com.insidejoke.admin.dto.CurrencyTotalDto;
import com.insidejoke.admin.dto.DayMetricsDto;
import com.insidejoke.admin.dto.FunnelMetricsDto;
import com.insidejoke.admin.dto.GameMetricsDto;
import com.insidejoke.admin.dto.MoneyMetricsDto;
import com.insidejoke.ai.AiCallRepository;
import com.insidejoke.auth.UserRepository;
import com.insidejoke.billing.PurchaseRepository;
import com.insidejoke.game.GameEngineService;
import com.insidejoke.game.GameSessionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Admin numbers for a date range (UTC days, both ends included): games, revenue, AI cost and conversion.
 * Conversion is hosts who bought a pass in the range divided by hosts who started a game in it.
 */
@Service
public class AdminMetricsService {

    private final GameSessionRepository games;
    private final PurchaseRepository purchases;
    private final AiCallRepository aiCalls;
    private final UserRepository users;
    private final GameEngineService engine;

    public AdminMetricsService(
            GameSessionRepository games,
            PurchaseRepository purchases,
            AiCallRepository aiCalls,
            UserRepository users,
            GameEngineService engine) {
        this.games = games;
        this.purchases = purchases;
        this.aiCalls = aiCalls;
        this.users = users;
        this.engine = engine;
    }

    public AdminMetricsDto metrics(LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        GameSessionRepository.GameTotals totals = games.totals(start, end);
        GameMetricsDto gameMetrics = new GameMetricsDto(
                totals.total(),
                totals.free(),
                totals.paid(),
                totals.completed(),
                totals.avgPlayers(),
                games.endReasons(start, end));

        List<CurrencyTotalDto> revenue = purchases.revenue(start, end).stream()
                .map(m -> new CurrencyTotalDto(m.currency(), m.minor()))
                .toList();
        Map<String, Integer> byProduct = purchases.countByProduct(start, end);
        int purchaseCount =
                byProduct.values().stream().mapToInt(Integer::intValue).sum();
        int refunds = purchases.refunds(start, end);

        AiCallRepository.AiTotals ai = aiCalls.totals(start, end);
        AiMetricsDto aiMetrics = new AiMetricsDto(
                ai.costMicros(),
                ai.freeGameCostMicros(),
                totals.total() == 0 ? 0 : ai.costMicros() / totals.total(),
                ai.calls(),
                ai.failures(),
                aiCalls.costByPurpose(start, end));

        int newHosts = users.countCreated(start, end);
        int active = games.activeHosts(start, end);
        int paying = purchases.payingUsers(start, end);
        FunnelMetricsDto funnel = new FunnelMetricsDto(
                newHosts, active, paying, active == 0 ? 0 : Math.round(paying * 1000.0 / active) / 1000.0);

        return new AdminMetricsDto(
                from,
                to,
                gameMetrics,
                new MoneyMetricsDto(revenue, purchaseCount, byProduct, refunds),
                aiMetrics,
                funnel,
                engine.liveStats(),
                days(from, to, start, end));
    }

    private List<DayMetricsDto> days(LocalDate from, LocalDate to, Instant start, Instant end) {
        Map<LocalDate, GameSessionRepository.DayCount> perDay = new LinkedHashMap<>();
        for (GameSessionRepository.DayCount d : games.perDay(start, end)) {
            perDay.put(d.day(), d);
        }
        Map<LocalDate, Long> cost = aiCalls.costPerDay(start, end);
        List<DayMetricsDto> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            GameSessionRepository.DayCount g = perDay.get(d);
            days.add(new DayMetricsDto(
                    d, g == null ? 0 : g.games(), g == null ? 0 : g.paidGames(), cost.getOrDefault(d, 0L)));
        }
        return days;
    }
}
