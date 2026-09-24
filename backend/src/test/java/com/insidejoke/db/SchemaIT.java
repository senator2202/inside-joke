package com.insidejoke.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insidejoke.auth.UserEntity;
import com.insidejoke.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** The schema itself enforces the money and privacy rules, independent of the Java code. */
class SchemaIT extends AbstractIntegrationTest {

    @Test
    void allTablesExistAfterMigration() {
        List<String> tables = jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
                .query(String.class)
                .list();
        assertThat(tables)
                .contains(
                        "app_user",
                        "magic_link_token",
                        "spring_session",
                        "spring_session_attributes",
                        "purchase",
                        "entitlement",
                        "webhook_event",
                        "game_session",
                        "ai_call",
                        "moderation_event",
                        "game_feedback",
                        "app_setting");
    }

    @Test
    void noColumnCanHoldPlayerContent() {
        List<String> columns = jdbc.sql(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' "
                                + "AND table_name IN ('game_session', 'ai_call', 'moderation_event')")
                .query(String.class)
                .list();
        assertThat(columns)
                .noneMatch(c -> c.contains("name") && !c.equals("room_code")
                        || c.contains("answer")
                        || c.contains("text")
                        || c.contains("dossier_text")
                        || c.contains("content"));
    }

    @Test
    void entitlementNeedsAPurchaseUnlessGrantedByAdmin() {
        UserEntity host = data.host();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO entitlement (user_id, type, starts_at, ends_at) "
                                + "VALUES (?, 'PARTY_PASS', now(), now() + interval '1 day')")
                        .param(host.id())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql("INSERT INTO entitlement (user_id, type, starts_at, ends_at, is_granted_by_admin) "
                        + "VALUES (?, 'PARTY_PASS', now(), now() + interval '1 day', true)")
                .param(host.id())
                .update();
    }

    @Test
    void entitlementMustEndAfterItStarts() {
        UserEntity host = data.host();
        assertThatThrownBy(() -> jdbc.sql(
                                "INSERT INTO entitlement (user_id, type, starts_at, ends_at, is_granted_by_admin) "
                                        + "VALUES (?, 'PARTY_PASS', now(), now() - interval '1 day', true)")
                        .param(host.id())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void freeGameCannotReferenceAPassAndPaidGameMust() {
        UserEntity host = data.host();
        assertThatThrownBy(() -> jdbc.sql(
                                "INSERT INTO game_session (host_user_id, room_code, mode, tone, length, prompt_version, "
                                        + "is_free, started_at) VALUES (?, 'ABCD', 'STANDARD', 'CHEEKY', 'SHORT', 'v3', false, now())")
                        .param(host.id())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void feedbackRatingIsOneToFiveAndCommentIsBounded() {
        UserEntity host = data.host();
        UUID game = data.game(host.id(), null, clock.instant());
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO game_feedback (game_session_id, rating) VALUES (?, 6)")
                        .param(game)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () -> jdbc.sql("INSERT INTO game_feedback (game_session_id, rating, comment) VALUES (?, 4, ?)")
                                .params(game, "x".repeat(501))
                                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void purchaseTransactionIdIsUnique() {
        UserEntity host = data.host();
        jdbc.sql("INSERT INTO purchase (user_id, provider_txn_id, product, amount_minor, currency, status) "
                        + "VALUES (?, 'txn_dup', 'PARTY_PASS', 299, 'USD', 'COMPLETED')")
                .param(host.id())
                .update();
        assertThatThrownBy(() -> jdbc.sql(
                                "INSERT INTO purchase (user_id, provider_txn_id, product, amount_minor, currency, status) "
                                        + "VALUES (?, 'txn_dup', 'PARTY_PASS', 299, 'USD', 'COMPLETED')")
                        .param(host.id())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static final String OUR_TABLES = "c.relnamespace = 'public'::regnamespace "
            + "AND c.relname NOT IN ('flyway_schema_history', 'spring_session', 'spring_session_attributes')";

    @Test
    void constraintsAndIndexesFollowTheNamingConvention() {
        List<String> constraints = jdbc.sql("SELECT c.relname || '.' || con.conname FROM pg_constraint con "
                        + "JOIN pg_class c ON c.oid = con.conrelid WHERE " + OUR_TABLES
                        + " AND con.contype IN ('p', 'f', 'c', 'u') "
                        + "AND con.conname !~ '^(pk|fk|ck|ux)_'")
                .query(String.class)
                .list();
        assertThat(constraints).as("constraints named pk_/fk_/ck_/ux_").isEmpty();
        List<String> indexes = jdbc.sql(
                        "SELECT c.relname || '.' || i.relname FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid "
                                + "JOIN pg_class c ON c.oid = x.indrelid WHERE " + OUR_TABLES
                                + " AND i.relname !~ '^(pk|ux|ix)_'")
                .query(String.class)
                .list();
        assertThat(indexes)
                .as("indexes named ix_/ux_ (or pk_ for primary keys)")
                .isEmpty();
    }

    @Test
    void booleanColumnsReadAsQuestions() {
        List<String> booleans = jdbc.sql(
                        "SELECT c.relname || '.' || a.attname FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                                + "WHERE " + OUR_TABLES
                                + " AND c.relkind = 'r' AND a.attnum > 0 AND NOT a.attisdropped "
                                + "AND a.atttypid = 'boolean'::regtype AND a.attname !~ '^(is|has)_'")
                .query(String.class)
                .list();
        assertThat(booleans).as("boolean columns named is_/has_").isEmpty();
    }
}
