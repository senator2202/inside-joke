package com.insidejoke.billing;

import com.insidejoke.ai.AiSpendService;
import com.insidejoke.billing.dto.AccessStatusDto;
import com.insidejoke.billing.dto.PassStatusDto;
import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.game.GameAccessPort;
import com.insidejoke.game.GameSessionRepository;
import com.insidejoke.settings.AppSettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides whether a host may start a game (blueprint 7.3), checked when the first round starts:
 * an active Host Pass with games left this month, else an active Party Pass, else the weekly free game
 * if free games are on and today's free AI budget isn't spent, else a paywall.
 */
@Service
public class GameAccessService implements GameAccessPort {

    public static final Duration FREE_GAME_WINDOW = Duration.ofDays(7);

    /** Outcome of the access check. */
    public sealed interface Decision permits Paid, Free, Denied {}

    public record Paid(EntitlementEntity entitlement) implements Decision {}

    public record Free() implements Decision {}

    public record Denied(ErrorCode reason) implements Decision {}

    /** What the room engine knows when the first round starts. */
    private final EntitlementRepository entitlements;

    private final GameSessionRepository games;
    private final AppSettingsService settings;
    private final AiSpendService aiSpend;
    private final Clock clock;

    public GameAccessService(
            EntitlementRepository entitlements,
            GameSessionRepository games,
            AppSettingsService settings,
            AiSpendService aiSpend,
            Clock clock) {
        this.entitlements = entitlements;
        this.games = games;
        this.settings = settings;
        this.aiSpend = aiSpend;
        this.clock = clock;
    }

    /**
     * Checks access and records the game in one transaction. A per-host advisory lock makes two simultaneous
     * starts (two rooms, or a double click) see each other, so one free game cannot be spent twice.
     */
    @Transactional
    @Override
    public Started start(StartParams req) {
        entitlements.lockHost(req.hostUserId());
        Instant now = clock.instant();
        Decision decision = evaluate(req.hostUserId(), now).decision();
        return switch (decision) {
            case Denied denied ->
                throw new ApiException(
                        denied.reason(),
                        denied.reason().defaultMessage(),
                        Map.of("reason", denied.reason().name()));
            case Free free -> new Started(games.insert(sessionRow(req, true, null, now)), true, null);
            case Paid paid ->
                new Started(
                        games.insert(sessionRow(req, false, paid.entitlement().id(), now)),
                        false,
                        paid.entitlement().type().name());
        };
    }

    public AccessStatusDto status(UUID hostUserId) {
        Instant now = clock.instant();
        Evaluation e = evaluate(hostUserId, now);
        String next = switch (e.decision()) {
            case Paid p -> p.entitlement().type().name();
            case Free f -> "FREE";
            case Denied d -> "PAYWALL";
        };
        ErrorCode reason = e.decision() instanceof Denied d ? d.reason() : null;
        return new AccessStatusDto(
                e.freeGamesEnabled(), e.freeGameAvailable(), e.nextFreeGameAt(), e.passes(), next, reason);
    }

    private record Evaluation(
            Decision decision,
            boolean freeGamesEnabled,
            boolean freeGameAvailable,
            Instant nextFreeGameAt,
            List<PassStatusDto> passes) {}

    private Evaluation evaluate(UUID hostUserId, Instant now) {
        Instant monthStart = YearMonth.from(now.atZone(ZoneOffset.UTC))
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        List<PassStatusDto> passes = new ArrayList<>();
        EntitlementEntity hostPassWithGames = null;
        EntitlementEntity partyPass = null;
        boolean monthlyExhausted = false;
        for (EntitlementEntity e : entitlements.findActive(hostUserId, now)) {
            Integer left = null;
            if (e.monthlyGameLimit() != null) {
                left = Math.max(0, e.monthlyGameLimit() - games.countForEntitlementSince(e.id(), monthStart));
            }
            passes.add(new PassStatusDto(e.id(), e.type(), e.endsAt(), e.monthlyGameLimit(), left, e.grantedByAdmin()));
            if (e.type() == Product.HOST_PASS) {
                if (left == null || left > 0) {
                    if (hostPassWithGames == null) {
                        hostPassWithGames = e;
                    }
                } else {
                    monthlyExhausted = true;
                }
            } else if (partyPass == null) {
                partyPass = e;
            }
        }

        AppSettingsService.Snapshot s = settings.get();
        Instant windowStart = now.minus(FREE_GAME_WINDOW);
        boolean freeUsed = games.countFreeSince(hostUserId, windowStart) > 0;
        Instant nextFree = freeUsed
                ? games.oldestFreeSince(hostUserId, windowStart)
                        .map(t -> t.plus(FREE_GAME_WINDOW))
                        .orElse(null)
                : null;
        boolean freeAvailable = s.freeGamesEnabled() && !freeUsed;

        Decision decision;
        if (hostPassWithGames != null) {
            decision = new Paid(hostPassWithGames);
        } else if (partyPass != null) {
            decision = new Paid(partyPass);
        } else if (freeAvailable && !aiSpend.freeBudgetExhausted()) {
            decision = new Free();
        } else if (monthlyExhausted) {
            decision = new Denied(ErrorCode.PAYWALL_MONTHLY_LIMIT);
        } else if (freeUsed) {
            decision = new Denied(ErrorCode.PAYWALL_FREE_LIMIT);
        } else {
            decision = new Denied(ErrorCode.BUDGET_PAUSED);
        }
        return new Evaluation(decision, s.freeGamesEnabled(), freeAvailable, nextFree, List.copyOf(passes));
    }

    private static GameSessionRepository.Start sessionRow(
            StartParams req, boolean free, UUID entitlementId, Instant now) {
        return new GameSessionRepository.Start(
                req.hostUserId(),
                req.previousSessionId(),
                req.roomCode(),
                req.mode(),
                req.tone(),
                req.length(),
                req.promptVersion(),
                free,
                entitlementId,
                req.playerCount(),
                now,
                req.language());
    }
}
