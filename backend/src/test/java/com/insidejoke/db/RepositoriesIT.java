package com.insidejoke.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.ai.AiCallEntity;
import com.insidejoke.ai.AiCallRepository;
import com.insidejoke.ai.AiOutcome;
import com.insidejoke.ai.AiPurpose;
import com.insidejoke.auth.UserEntity;
import com.insidejoke.billing.EntitlementEntity;
import com.insidejoke.billing.EntitlementRepository;
import com.insidejoke.billing.Product;
import com.insidejoke.billing.PurchaseEntity;
import com.insidejoke.billing.PurchaseRepository;
import com.insidejoke.billing.PurchaseStatus;
import com.insidejoke.billing.RevokeReason;
import com.insidejoke.billing.WebhookEventRepository;
import com.insidejoke.game.EndReason;
import com.insidejoke.game.FeedbackRepository;
import com.insidejoke.game.GameSessionRepository;
import com.insidejoke.moderation.ModerationAction;
import com.insidejoke.moderation.ModerationEventRepository;
import com.insidejoke.moderation.ModerationStage;
import com.insidejoke.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RepositoriesIT extends AbstractIntegrationTest {

    @Autowired
    PurchaseRepository purchases;

    @Autowired
    EntitlementRepository entitlements;

    @Autowired
    WebhookEventRepository webhooks;

    @Autowired
    GameSessionRepository games;

    @Autowired
    FeedbackRepository feedback;

    @Autowired
    AiCallRepository aiCalls;

    @Autowired
    ModerationEventRepository moderation;

    @Test
    void purchaseIsRecordedOncePerTransactionAndRefundedOnce() {
        UserEntity host = data.host();
        String txn = "txn_" + UUID.randomUUID();
        Optional<PurchaseEntity> first =
                purchases.insertIfAbsent(host.id(), txn, Product.HOST_PASS, 1499, "EUR", clock.instant());
        assertThat(first).isPresent();
        assertThat(first.get().amount().currency()).isEqualTo("EUR");
        assertThat(purchases.insertIfAbsent(host.id(), txn, Product.HOST_PASS, 1499, "EUR", clock.instant()))
                .isEmpty();

        assertThat(purchases.markRefunded(txn, clock.instant(), RevokeReason.REFUND))
                .map(PurchaseEntity::status)
                .contains(PurchaseStatus.REFUNDED);
        assertThat(purchases.markRefunded(txn, clock.instant(), RevokeReason.REFUND))
                .isEmpty();
        assertThat(purchases.listByUser(host.id()))
                .singleElement()
                .extracting(PurchaseEntity::status)
                .isEqualTo(PurchaseStatus.REFUNDED);
    }

    @Test
    void entitlementsAreFoundWhileActiveAndRevokedByPurchase() {
        UserEntity host = data.host();
        EntitlementEntity pass = data.pass(host.id(), Product.PARTY_PASS);
        assertThat(pass.monthlyGameLimit()).isNull();
        assertThat(pass.endsAt()).isEqualTo(pass.startsAt().plus(Duration.ofHours(24)));
        assertThat(entitlements.findActive(host.id(), clock.instant()))
                .extracting(EntitlementEntity::id)
                .containsExactly(pass.id());
        assertThat(entitlements.findActive(host.id(), clock.instant().plus(Duration.ofHours(25))))
                .isEmpty();

        assertThat(entitlements.revokeByPurchase(pass.purchaseId(), clock.instant(), RevokeReason.REFUND))
                .isEqualTo(1);
        assertThat(entitlements.revokeByPurchase(pass.purchaseId(), clock.instant(), RevokeReason.REFUND))
                .isZero();
        assertThat(entitlements.findActive(host.id(), clock.instant())).isEmpty();
        assertThat(entitlements.findByPurchase(pass.purchaseId()))
                .get()
                .extracting(EntitlementEntity::revokedAt)
                .isNotNull();
    }

    @Test
    void hostPassCarriesTheMonthlyLimit() {
        UserEntity host = data.host();
        EntitlementEntity pass = data.pass(host.id(), Product.HOST_PASS);
        assertThat(pass.monthlyGameLimit()).isEqualTo(15);
        assertThat(pass.endsAt()).isEqualTo(pass.startsAt().plus(Duration.ofDays(365)));
    }

    @Test
    void webhookEventsAreStoredOnceAndTrackProcessing() {
        String id = "evt_" + UUID.randomUUID();
        assertThat(webhooks.insertIfAbsent("PADDLE", id, "transaction.completed", "{\"a\":1}", clock.instant()))
                .isTrue();
        assertThat(webhooks.insertIfAbsent("PADDLE", id, "transaction.completed", "{\"a\":1}", clock.instant()))
                .isFalse();
        assertThat(webhooks.isProcessed("PADDLE", id)).isFalse();
        webhooks.markFailed("PADDLE", id, "boom");
        assertThat(jdbc.sql("SELECT last_error FROM webhook_event WHERE event_id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("boom");
        webhooks.markProcessed("PADDLE", id, clock.instant(), WebhookEventRepository.Outcome.APPLIED, null, null);
        assertThat(webhooks.isProcessed("PADDLE", id)).isTrue();
        assertThat(webhooks.isProcessed("PADDLE", "evt_unknown")).isFalse();
    }

    @Test
    void gameSessionIsFinishedOnceAndCountsFreeGames() {
        UserEntity host = data.host();
        UUID game = data.game(host.id(), null, clock.instant().minus(Duration.ofDays(2)));
        data.game(host.id(), null, clock.instant().minus(Duration.ofDays(9)));
        assertThat(games.countFreeSince(host.id(), clock.instant().minus(Duration.ofDays(7))))
                .isEqualTo(1);
        assertThat(games.oldestFreeSince(host.id(), clock.instant().minus(Duration.ofDays(7))))
                .get()
                .satisfies(t -> assertThat(Duration.between(t, clock.instant()).toHours())
                        .isBetween(47L, 48L));
        assertThat(games.findHost(game)).contains(host.id());

        GameSessionRepository.Finish finish =
                new GameSessionRepository.Finish(5, 0, 5, 12, EndReason.COMPLETED, clock.instant());
        assertThat(games.finish(game, finish)).isTrue();
        assertThat(games.finish(game, new GameSessionRepository.Finish(2, 0, 1, 0, EndReason.IDLE, clock.instant())))
                .isFalse();
        assertThat(jdbc.sql("SELECT end_reason FROM game_session WHERE id = ?")
                        .param(game)
                        .query(String.class)
                        .single())
                .isEqualTo("COMPLETED");
    }

    @Test
    void feedbackUpsertKeepsOneRatingPerGame() {
        UserEntity host = data.host();
        UUID game = data.game(host.id(), null, clock.instant());
        feedback.upsert(game, 3, null, clock.instant());
        feedback.upsert(game, 5, "The secrets round killed", clock.instant());
        assertThat(jdbc.sql("SELECT rating FROM game_feedback WHERE game_session_id = ?")
                        .param(game)
                        .query(Integer.class)
                        .list())
                .containsExactly(5);
    }

    @Test
    void aiCallsAreSummedPerGameAndForTheFreeBudget() {
        UserEntity host = data.host();
        UUID game = data.game(host.id(), null, clock.instant());
        long before = aiCalls.freeGameCostSince(clock.instant().minus(Duration.ofMinutes(1)));
        aiCalls.insert(
                new AiCallEntity(
                        game,
                        AiPurpose.ROUND_GEN,
                        "anthropic",
                        "m",
                        "round_gen.v3",
                        900,
                        400,
                        null,
                        4_500,
                        2100,
                        AiOutcome.OK,
                        true),
                clock.instant());
        aiCalls.insert(
                new AiCallEntity(
                        game, AiPurpose.TTS, "tts", "voice", null, null, null, 120, 1_800, 600, AiOutcome.OK, true),
                clock.instant());
        aiCalls.insert(
                new AiCallEntity(
                        null,
                        AiPurpose.MODERATION,
                        "anthropic",
                        "m",
                        "moderation.v1",
                        100,
                        5,
                        null,
                        90,
                        300,
                        AiOutcome.OK,
                        false),
                clock.instant());
        assertThat(aiCalls.costForGame(game)).isEqualTo(6_300);
        assertThat(aiCalls.freeGameCostSince(clock.instant().minus(Duration.ofMinutes(1))) - before)
                .isEqualTo(6_300);
    }

    @Test
    void moderationEventsStoreOnlyCategories() {
        UserEntity host = data.host();
        UUID game = data.game(host.id(), null, clock.instant());
        moderation.insert(game, ModerationStage.DOSSIER, "contact_info", ModerationAction.BLOCKED, clock.instant());
        assertThat(jdbc.sql("SELECT category FROM moderation_event WHERE game_session_id = ?")
                        .param(game)
                        .query(String.class)
                        .list())
                .containsExactly("contact_info");
    }
}
