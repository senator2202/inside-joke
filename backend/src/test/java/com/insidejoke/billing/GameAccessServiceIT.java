package com.insidejoke.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insidejoke.ai.AiCallEntity;
import com.insidejoke.ai.AiCallRepository;
import com.insidejoke.ai.AiOutcome;
import com.insidejoke.ai.AiPurpose;
import com.insidejoke.auth.UserEntity;
import com.insidejoke.billing.dto.AccessStatusDto;
import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.common.Language;
import com.insidejoke.game.GameLength;
import com.insidejoke.game.RoomMode;
import com.insidejoke.game.Tone;
import com.insidejoke.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GameAccessServiceIT extends AbstractIntegrationTest {

    @Autowired
    GameAccessService access;

    @Autowired
    EntitlementRepository entitlements;

    @Autowired
    AiCallRepository aiCalls;

    private GameAccessService.StartParams request(UUID host) {
        return new GameAccessService.StartParams(
                host, null, "ABCD", RoomMode.STANDARD, Tone.CHEEKY, GameLength.SHORT, "round_gen.v3", 4, Language.EN);
    }

    private ErrorCode denial(UUID host) {
        try {
            access.start(request(host));
            throw new AssertionError("expected a paywall");
        } catch (ApiException e) {
            return e.code();
        }
    }

    @Test
    void newHostGetsOneFreeGamePerSevenDays() {
        UserEntity host = data.host();
        assertThat(access.status(host.id()).nextGame()).isEqualTo("FREE");

        GameAccessService.Started first = access.start(request(host.id()));
        assertThat(first.free()).isTrue();
        assertThat(first.passType()).isNull();
        Instant firstAt = clock.instant();

        AccessStatusDto afterFree = access.status(host.id());
        assertThat(afterFree.freeGameAvailable()).isFalse();
        assertThat(afterFree.paywallReason()).isEqualTo(ErrorCode.PAYWALL_FREE_LIMIT);
        assertThat(afterFree.nextFreeGameAt())
                .isBetween(
                        firstAt.plus(Duration.ofDays(7)).minusSeconds(5),
                        firstAt.plus(Duration.ofDays(7)).plusSeconds(5));
        assertThat(denial(host.id())).isEqualTo(ErrorCode.PAYWALL_FREE_LIMIT);

        clock.advance(Duration.ofDays(6).plusHours(23));
        assertThat(denial(host.id())).isEqualTo(ErrorCode.PAYWALL_FREE_LIMIT);

        clock.advance(Duration.ofHours(2));
        assertThat(access.start(request(host.id())).free()).isTrue();
    }

    @Test
    void partyPassIsUnlimitedFor24Hours() {
        UserEntity host = data.host();
        access.start(request(host.id()));
        EntitlementEntity pass = data.pass(host.id(), Product.PARTY_PASS);

        for (int i = 0; i < 20; i++) {
            GameAccessService.Started started = access.start(request(host.id()));
            assertThat(started.free()).isFalse();
            assertThat(started.passType()).isEqualTo(Product.PARTY_PASS.name());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM game_session WHERE entitlement_id = ?")
                        .param(pass.id())
                        .query(Integer.class)
                        .single())
                .isEqualTo(20);

        clock.advance(Duration.ofHours(24).plusMinutes(1));
        assertThat(denial(host.id())).isEqualTo(ErrorCode.PAYWALL_FREE_LIMIT);
    }

    @Test
    void hostPassAllowsFifteenGamesPerCalendarMonth() {
        ZonedDateTime midMonth = ZonedDateTime.of(2027, 3, 14, 20, 0, 0, 0, ZoneOffset.UTC);
        clock.setNow(midMonth.toInstant());
        UserEntity host = data.host();
        EntitlementEntity pass =
                data.pass(host.id(), Product.HOST_PASS, midMonth.minusMonths(2).toInstant());
        data.game(host.id(), null, clock.instant().minus(Duration.ofDays(1)));
        for (int i = 0; i < 5; i++) {
            data.game(host.id(), pass, midMonth.minusMonths(1).toInstant());
        }

        for (int i = 0; i < 15; i++) {
            assertThat(access.start(request(host.id())).passType()).isEqualTo(Product.HOST_PASS.name());
        }
        AccessStatusDto status = access.status(host.id());
        assertThat(status.passes()).singleElement().satisfies(p -> {
            assertThat(p.type()).isEqualTo(Product.HOST_PASS);
            assertThat(p.monthlyGameLimit()).isEqualTo(15);
            assertThat(p.gamesLeftThisMonth()).isZero();
        });
        assertThat(denial(host.id())).isEqualTo(ErrorCode.PAYWALL_MONTHLY_LIMIT);

        clock.setNow(ZonedDateTime.of(2027, 4, 1, 0, 0, 1, 0, ZoneOffset.UTC).toInstant());
        assertThat(access.start(request(host.id())).passType()).isEqualTo(Product.HOST_PASS.name());
        assertThat(access.status(host.id()).passes().getFirst().gamesLeftThisMonth())
                .isEqualTo(14);
    }

    @Test
    void exhaustedHostPassFallsBackToPartyPassThenFreeGame() {
        UserEntity host = data.host();
        EntitlementEntity hostPass =
                data.pass(host.id(), Product.HOST_PASS, clock.instant().minus(Duration.ofDays(1)));
        for (int i = 0; i < 15; i++) {
            data.game(host.id(), hostPass, clock.instant());
        }
        assertThat(access.start(request(host.id())).free()).isTrue();
        assertThat(denial(host.id())).isEqualTo(ErrorCode.PAYWALL_MONTHLY_LIMIT);

        data.pass(host.id(), Product.PARTY_PASS);
        assertThat(access.start(request(host.id())).passType()).isEqualTo(Product.PARTY_PASS.name());
    }

    @Test
    void aRunningPartyPassIsSpentBeforeTheHostPassGames() {
        UserEntity host = data.host();
        data.pass(host.id(), Product.HOST_PASS);
        data.pass(host.id(), Product.PARTY_PASS);
        assertThat(access.start(request(host.id())).passType()).isEqualTo(Product.PARTY_PASS.name());
        assertThat(access.status(host.id()).nextGame()).isEqualTo(Product.PARTY_PASS.name());
        assertThat(access.status(host.id()).passes())
                .filteredOn(p -> p.type() == Product.HOST_PASS)
                .singleElement()
                .satisfies(p -> assertThat(p.gamesLeftThisMonth())
                        .as("no Host Pass game used")
                        .isEqualTo(15));
    }

    @Test
    void revokedPassNoLongerCounts() {
        UserEntity host = data.host();
        data.game(host.id(), null, clock.instant());
        EntitlementEntity pass = data.pass(host.id(), Product.PARTY_PASS);
        entitlements.revokeByPurchase(pass.purchaseId(), clock.instant(), RevokeReason.REFUND);
        assertThat(denial(host.id())).isEqualTo(ErrorCode.PAYWALL_FREE_LIMIT);
        assertThat(access.status(host.id()).passes()).isEmpty();
    }

    @Test
    void freeGamesPauseWhenDisabledOrOverDailyBudgetButPaidGamesContinue() {
        UserEntity host = data.host();
        settings.update("free_games_enabled", json.readTree("false"));
        assertThat(denial(host.id())).isEqualTo(ErrorCode.BUDGET_PAUSED);
        assertThat(access.status(host.id()).freeGamesEnabled()).isFalse();

        settings.update("free_games_enabled", json.readTree("true"));
        long spent = aiSpend.freeSpendTodayMicros();
        settings.update("daily_free_ai_budget_micros", json.readTree(Long.toString(spent + 1_000)));
        aiCalls.insert(
                new AiCallEntity(
                        null,
                        AiPurpose.ROUND_GEN,
                        "anthropic",
                        "test-model",
                        "round_gen.v3",
                        100,
                        100,
                        null,
                        1_000,
                        800,
                        AiOutcome.OK,
                        true),
                clock.instant());
        aiSpend.invalidate();
        assertThat(denial(host.id())).isEqualTo(ErrorCode.BUDGET_PAUSED);

        data.pass(host.id(), Product.PARTY_PASS);
        assertThat(access.start(request(host.id())).free()).isFalse();
    }

    @Test
    void simultaneousStartsSpendTheFreeGameOnlyOnce() throws Exception {
        UserEntity host = data.host();
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            attempts.add(() -> {
                try {
                    access.start(request(host.id()));
                    return true;
                } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo(ErrorCode.PAYWALL_FREE_LIMIT);
                    return false;
                }
            });
        }
        int succeeded = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            for (Future<Boolean> f : pool.invokeAll(attempts)) {
                succeeded += f.get() ? 1 : 0;
            }
        }
        assertThat(succeeded).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM game_session WHERE host_user_id = ?")
                        .param(host.id())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void deniedStartCarriesTheReasonForThePaywall() {
        UserEntity host = data.host();
        access.start(request(host.id()));
        assertThatThrownBy(() -> access.start(request(host.id())))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.details()).containsEntry("reason", "PAYWALL_FREE_LIMIT"));
    }
}
