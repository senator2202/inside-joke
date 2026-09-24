package com.insidejoke.support;

import com.insidejoke.auth.UserEntity;
import com.insidejoke.auth.UserRepository;
import com.insidejoke.billing.EntitlementEntity;
import com.insidejoke.billing.EntitlementRepository;
import com.insidejoke.billing.Product;
import com.insidejoke.billing.PurchaseEntity;
import com.insidejoke.billing.PurchaseRepository;
import com.insidejoke.common.Language;
import com.insidejoke.game.GameLength;
import com.insidejoke.game.GameSessionRepository;
import com.insidejoke.game.RoomMode;
import com.insidejoke.game.Tone;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builders for database rows used across integration tests. */
@Component
public class TestData {

    private final UserRepository users;
    private final PurchaseRepository purchases;
    private final EntitlementRepository entitlements;
    private final GameSessionRepository games;
    private final Clock clock;

    public TestData(
            UserRepository users,
            PurchaseRepository purchases,
            EntitlementRepository entitlements,
            GameSessionRepository games,
            Clock clock) {
        this.users = users;
        this.purchases = purchases;
        this.entitlements = entitlements;
        this.games = games;
        this.clock = clock;
    }

    public UserEntity host() {
        String email = "host-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        return users.insert(email, null, null, "HOST", clock.instant());
    }

    public PurchaseEntity purchase(UUID userId, Product product) {
        return purchases
                .insertIfAbsent(
                        userId, "txn_" + UUID.randomUUID(), product, product.priceUsdCents(), "USD", clock.instant())
                .orElseThrow();
    }

    /** A bought pass starting at {@code start}. */
    public EntitlementEntity pass(UUID userId, Product product, Instant start) {
        PurchaseEntity p = purchase(userId, product);
        return entitlements.insert(userId, p.id(), product, start, start.plus(product.validity()), clock.instant());
    }

    public EntitlementEntity pass(UUID userId, Product product) {
        return pass(userId, product, clock.instant());
    }

    public UUID game(UUID hostId, EntitlementEntity paidWith, Instant startedAt) {
        return games.insert(new GameSessionRepository.Start(
                hostId,
                null,
                "TEST",
                RoomMode.STANDARD,
                Tone.CHEEKY,
                GameLength.SHORT,
                "round_gen.v3",
                paidWith == null,
                paidWith == null ? null : paidWith.id(),
                4,
                startedAt,
                Language.EN));
    }
}
