-- Who granted or revoked a pass and why, how a purchase was refunded, and what the server did with each webhook.

ALTER TABLE entitlement
    ADD COLUMN granted_by     uuid REFERENCES app_user(id),
    ADD COLUMN revoked_by     uuid REFERENCES app_user(id),
    ADD COLUMN revoke_reason  text;
ALTER TABLE entitlement ADD CONSTRAINT ck_entitlement_revoke_reason
    CHECK (revoke_reason IS NULL OR revoke_reason IN ('REFUND', 'CHARGEBACK', 'ADMIN', 'ACCOUNT_DELETED'));
ALTER TABLE entitlement ADD CONSTRAINT ck_entitlement_revoke_consistent
    CHECK (revoke_reason IS NULL OR revoked_at IS NOT NULL);

ALTER TABLE purchase
    ADD COLUMN refunded_at  timestamptz,
    ADD COLUMN refund_kind  text;
ALTER TABLE purchase ADD CONSTRAINT ck_purchase_refund_kind
    CHECK (refund_kind IS NULL OR refund_kind IN ('REFUND', 'CHARGEBACK'));

ALTER TABLE webhook_event
    ADD COLUMN outcome  text,
    ADD COLUMN reason   text,
    ADD COLUMN detail   text;
ALTER TABLE webhook_event ADD CONSTRAINT ck_webhook_event_outcome
    CHECK (outcome IS NULL OR outcome IN ('APPLIED', 'NOT_APPLIED', 'DUPLICATE', 'IGNORED', 'FAILED'));

-- Backfill what can be known about history recorded before this migration.
UPDATE purchase SET refunded_at = updated_at, refund_kind = 'REFUND' WHERE status = 'REFUNDED';
UPDATE entitlement e SET revoke_reason = 'REFUND'
    FROM purchase p WHERE e.purchase_id = p.id AND p.status = 'REFUNDED' AND e.revoked_at IS NOT NULL;
UPDATE entitlement e SET revoke_reason = 'ACCOUNT_DELETED'
    FROM app_user u WHERE e.user_id = u.id AND u.deleted_at IS NOT NULL AND e.revoked_at IS NOT NULL AND e.revoke_reason IS NULL;
UPDATE webhook_event SET outcome = 'FAILED' WHERE processed_at IS NULL AND last_error IS NOT NULL;

-- Admin lists: passes by date, refunds/chargebacks the server did not apply.
CREATE INDEX ix_entitlement_created ON entitlement (created_at DESC, id);
CREATE INDEX ix_webhook_not_applied ON webhook_event (received_at DESC) WHERE outcome = 'NOT_APPLIED';
CREATE INDEX ix_purchase_txn_refund ON purchase (refunded_at) WHERE refunded_at IS NOT NULL;
CREATE INDEX ix_game_entitlement ON game_session (entitlement_id) WHERE entitlement_id IS NOT NULL;
