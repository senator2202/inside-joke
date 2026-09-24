package com.insidejoke.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.auth.UserRepository;
import com.insidejoke.billing.EntitlementEntity;
import com.insidejoke.billing.EntitlementRepository;
import com.insidejoke.billing.PaddleSignatureUtils;
import com.insidejoke.billing.Product;
import com.insidejoke.billing.PurchaseEntity;
import com.insidejoke.billing.PurchaseRepository;
import com.insidejoke.billing.RevokeReason;
import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.ApiClient;
import com.insidejoke.support.Party;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class AdminPassesIT extends AbstractIntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    PurchaseRepository purchases;

    @Autowired
    EntitlementRepository entitlements;

    private ApiClient admin() {
        return Party.signIn(port, json, FAKE, "boss@example.com");
    }

    private UUID user(String tag, String name) {
        return users.insert(tag + "-" + name + "@passes.test", null, null, "HOST", clock.instant())
                .id();
    }

    private EntitlementEntity bought(UUID user, Product product, int amount, String currency, Instant start) {
        PurchaseEntity p = purchases
                .insertIfAbsent(user, "txn_" + UUID.randomUUID(), product, amount, currency, clock.instant())
                .orElseThrow();
        return entitlements.insert(user, p.id(), product, start, start.plus(product.validity()), clock.instant());
    }

    private void refund(EntitlementEntity pass, RevokeReason kind) {
        String txn = jdbc.sql("SELECT provider_txn_id FROM purchase WHERE id = ?")
                .param(pass.purchaseId())
                .query(String.class)
                .single();
        purchases.markRefunded(txn, clock.instant(), kind);
        entitlements.revokeByPurchase(pass.purchaseId(), clock.instant(), kind);
    }

    /** Seven passes under one tag: every source and every status. */
    private record Seeded(String tag, EntitlementEntity aliceActive, EntitlementEntity frank) {}

    private Seeded seed(ApiClient admin) {
        String tag = "t" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = clock.instant();
        UUID alice = user(tag, "alice");
        UUID bob = user(tag, "bob");
        UUID carol = user(tag, "carol");
        UUID dave = user(tag, "dave");
        UUID erin = user(tag, "erin");
        UUID frank = user(tag, "frank");
        EntitlementEntity aliceActive = bought(alice, Product.PARTY_PASS, 299, "USD", now);
        bought(alice, Product.PARTY_PASS, 299, "USD", now.plus(Duration.ofHours(24)));
        data.game(alice, aliceActive, now);
        refund(bought(bob, Product.HOST_PASS, 1399, "EUR", now), RevokeReason.REFUND);
        admin.post("/api/admin/users/" + carol + "/passes", Map.of("type", "HOST_PASS"));
        bought(dave, Product.PARTY_PASS, 299, "USD", now.minus(Duration.ofDays(3)));
        refund(bought(erin, Product.PARTY_PASS, 299, "USD", now), RevokeReason.CHARGEBACK);
        EntitlementEntity frankPass = bought(frank, Product.HOST_PASS, 1499, "USD", now);
        assertThat(admin.post("/api/admin/passes/" + frankPass.id() + "/revoke", Map.of())
                        .status())
                .isEqualTo(200);
        return new Seeded(tag, aliceActive, frankPass);
    }

    private JsonNode list(ApiClient admin, String query) {
        return admin.get("/api/admin/passes?" + query).json();
    }

    private static List<String> emails(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.path("items")
                .forEach(i -> out.add(i.path("userEmail")
                        .asString()
                        .replaceFirst("^t[0-9a-f]+-", "")
                        .replace("@passes.test", "")));
        return out;
    }

    @Test
    void thePassLogFiltersSortsAndPaginatesOnTheServer() {
        ApiClient admin = admin();
        Seeded s = seed(admin);
        String t = "email=" + s.tag();

        JsonNode firstPage = list(admin, t + "&size=3");
        assertThat(firstPage.path("totalItems").asLong()).isEqualTo(7);
        assertThat(firstPage.path("totalPages").asInt()).isEqualTo(3);
        assertThat(firstPage.path("items").size()).isEqualTo(3);
        JsonNode lastPage = list(admin, t + "&size=3&page=2");
        assertThat(lastPage.path("items").size()).isEqualTo(1);
        assertThat(list(admin, t + "&size=3&page=9").path("items").size()).isZero();

        assertThat(emails(list(admin, t + "&status=REFUNDED&sort=email&dir=asc")))
                .containsExactly("bob", "erin");
        JsonNode refunded =
                list(admin, t + "&status=REFUNDED&sort=email&dir=asc").path("items");
        assertThat(refunded.get(0).path("refundKind").asString()).isEqualTo("REFUND");
        assertThat(refunded.get(0).path("currency").asString()).isEqualTo("EUR");
        assertThat(refunded.get(1).path("refundKind").asString()).isEqualTo("CHARGEBACK");
        assertThat(refunded.get(1).path("revokeReason").asString()).isEqualTo("CHARGEBACK");

        JsonNode revoked = list(admin, t + "&status=REVOKED").path("items");
        assertThat(revoked.size()).isEqualTo(1);
        assertThat(revoked.get(0).path("revokeReason").asString()).isEqualTo("ADMIN");
        assertThat(revoked.get(0).path("revokedByEmail").asString()).isEqualTo("boss@example.com");

        JsonNode granted = list(admin, t + "&source=GRANT").path("items");
        assertThat(granted.size()).isEqualTo(1);
        assertThat(granted.get(0).path("source").asString()).isEqualTo("GRANT");
        assertThat(granted.get(0).path("grantedByEmail").asString()).isEqualTo("boss@example.com");
        assertThat(granted.get(0).path("amountMinor").isNull()).isTrue();

        assertThat(emails(list(admin, t + "&status=UPCOMING"))).containsExactly("alice");
        assertThat(emails(list(admin, t + "&status=EXPIRED"))).containsExactly("dave");
        assertThat(emails(list(admin, t + "&status=ACTIVE&sort=email&dir=asc"))).containsExactly("alice", "carol");
        assertThat(emails(list(admin, t + "&type=host_pass&sort=email&dir=asc")))
                .containsExactly("bob", "carol", "frank");

        JsonNode byAmount = list(admin, t + "&sort=amount&dir=desc").path("items");
        assertThat(byAmount.get(0).path("amountMinor").asInt()).isEqualTo(1499);
        assertThat(byAmount.get(6).path("source").asString())
                .as("grants have no amount and sort last")
                .isEqualTo("GRANT");

        JsonNode aliceRow = null;
        for (JsonNode row : list(admin, t + "&status=ACTIVE").path("items")) {
            if (row.path("id").asString().equals(s.aliceActive().id().toString())) {
                aliceRow = row;
            }
        }
        assertThat(aliceRow).isNotNull();
        assertThat(aliceRow.path("gamesPlayed").asInt()).isEqualTo(1);
        assertThat(aliceRow.path("txnId").asString()).startsWith("txn_");

        String today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString();
        String tomorrow =
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(1).toString();
        assertThat(list(admin, t + "&from=" + today + "&to=" + today)
                        .path("totalItems")
                        .asLong())
                .isEqualTo(7);
        assertThat(list(admin, t + "&from=" + tomorrow).path("totalItems").asLong())
                .isZero();
    }

    @Test
    void theSummaryAddsUpTheSameFilters() {
        ApiClient admin = admin();
        Seeded s = seed(admin);
        JsonNode sum = admin.get("/api/admin/passes/summary?email=" + s.tag()).json();
        assertThat(sum.path("sold").asLong()).isEqualTo(6);
        assertThat(sum.path("granted").asLong()).isEqualTo(1);
        assertThat(sum.path("refunded").asLong()).isEqualTo(1);
        assertThat(sum.path("chargebacks").asLong()).isEqualTo(1);
        assertThat(sum.path("refundRate").asDouble()).isEqualTo(0.333);
        assertThat(sum.path("activeNow").asLong()).isEqualTo(2);
        JsonNode usd = sum.path("revenue").get(0);
        assertThat(usd.path("currency").asString()).isEqualTo("USD");
        assertThat(usd.path("grossMinor").asLong()).isEqualTo(299 * 4 + 1499);
        assertThat(usd.path("refundedMinor").asLong()).isEqualTo(299);
        assertThat(usd.path("netMinor").asLong()).isEqualTo(299 * 3 + 1499);
        JsonNode eur = sum.path("revenue").get(1);
        assertThat(eur.path("netMinor").asLong()).isZero();
        assertThat(sum.path("byType").path("PARTY_PASS").path("sold").asLong()).isEqualTo(4);
        assertThat(sum.path("byType").path("PARTY_PASS").path("chargebacks").asLong())
                .isEqualTo(1);
        assertThat(sum.path("byType").path("HOST_PASS").path("granted").asLong())
                .isEqualTo(1);
        assertThat(sum.path("byType").path("HOST_PASS").path("refunded").asLong())
                .isEqualTo(1);
        assertThat(admin.get("/api/admin/passes/summary?email=" + s.tag() + "&type=HOST_PASS")
                        .json()
                        .path("sold")
                        .asLong())
                .isEqualTo(2);
    }

    @Test
    void badParametersAreRefusedAndNonAdminsAreKeptOut() {
        ApiClient admin = admin();
        assertThat(admin.get("/api/admin/passes?status=BOGUS").errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(admin.get("/api/admin/passes?type=GOLD").errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(admin.get("/api/admin/passes?size=101").errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(admin.get("/api/admin/passes?page=-1").errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(admin.get("/api/admin/passes?sort=id;drop%20table%20purchase")
                        .errorCode())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(admin.get("/api/admin/passes?dir=sideways").errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(admin.get("/api/admin/passes?from=2031-03-07&to=2031-03-01").errorCode())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(admin.get("/api/admin/passes?email=50%25_off").status())
                .as("LIKE wildcards are escaped")
                .isEqualTo(200);
        ApiClient host = Party.signIn(port, json, FAKE, uniqueEmail("nosy"));
        assertThat(host.get("/api/admin/passes").status()).isEqualTo(403);
        assertThat(host.get("/api/admin/webhooks/not-applied").status()).isEqualTo(403);
    }

    @Test
    void deletedAccountsLoseTheirPassesForThatReason() {
        ApiClient host = Party.signIn(port, json, FAKE, uniqueEmail("leaving"));
        UUID id = UUID.fromString(host.get("/api/me").json().path("id").asString());
        data.pass(id, Product.HOST_PASS);
        assertThat(host.delete("/api/me").status()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT revoke_reason FROM entitlement WHERE user_id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("ACCOUNT_DELETED");
    }

    @Test
    void refundsTheServerDidNotApplyAreListedWithTheReasonAndTheirPass() {
        ApiClient admin = admin();
        UUID host = data.host().id();
        EntitlementEntity pass = data.pass(host, Product.PARTY_PASS);
        String txn = jdbc.sql("SELECT provider_txn_id FROM purchase WHERE id = ?")
                .param(pass.purchaseId())
                .query(String.class)
                .single();
        String eventId = "evt_na_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> event = Map.of(
                "event_id",
                eventId,
                "event_type",
                "adjustment.updated",
                "data",
                Map.of(
                        "id",
                        "adj_1",
                        "action",
                        "refund",
                        "status",
                        "approved",
                        "type",
                        "partial",
                        "transaction_id",
                        txn,
                        "items",
                        List.of(Map.of("type", "partial")),
                        "totals",
                        Map.of("total", "100", "currency_code", "USD")));
        byte[] body = json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        client().postRaw(
                        "/api/webhooks/paddle",
                        body,
                        "Paddle-Signature",
                        PaddleSignatureUtils.sign(body, "pdl_ntfset_test_secret", clock.instant()));

        JsonNode row =
                find(admin.get("/api/admin/webhooks/not-applied?size=100").json(), eventId);
        assertThat(row).as("listed while the customer still has the pass").isNotNull();
        assertThat(row.path("reason").asString()).isEqualTo("PARTIAL");
        assertThat(row.path("detail").asString()).contains("1.00 USD of 2.99 USD");
        assertThat(row.path("action").asString()).isEqualTo("refund");
        assertThat(row.path("userEmail").asString()).endsWith("@example.com");
        assertThat(row.path("passHeld").asBoolean()).isTrue();
        assertThat(row.path("passId").asString()).isEqualTo(pass.id().toString());

        admin.post("/api/admin/passes/" + pass.id() + "/revoke", Map.of());
        assertThat(find(admin.get("/api/admin/webhooks/not-applied?size=100").json(), eventId))
                .as("resolved by hand")
                .isNull();
        JsonNode all = find(
                admin.get("/api/admin/webhooks/not-applied?stillHeld=false&size=100")
                        .json(),
                eventId);
        assertThat(all.path("passHeld").asBoolean()).isFalse();
        assertThat(admin.get("/api/admin/webhooks/not-applied?size=0").errorCode())
                .isEqualTo("VALIDATION_FAILED");
    }

    private static JsonNode find(JsonNode page, String eventId) {
        for (JsonNode row : page.path("items")) {
            if (row.path("eventId").asString().equals(eventId)) {
                return row;
            }
        }
        return null;
    }
}
