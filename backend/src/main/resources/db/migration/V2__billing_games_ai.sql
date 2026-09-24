-- Money, game summaries (never their content), AI spend, moderation counts, feedback and runtime flags.

CREATE TABLE purchase (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid NOT NULL REFERENCES app_user(id),
    provider         text NOT NULL DEFAULT 'PADDLE',
    provider_txn_id  text NOT NULL UNIQUE,
    product          text NOT NULL CHECK (product IN ('PARTY_PASS', 'HOST_PASS')),
    amount_minor     integer NOT NULL CHECK (amount_minor >= 0),
    currency         char(3) NOT NULL,
    status           text NOT NULL CHECK (status IN ('COMPLETED', 'REFUNDED')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_purchase_user ON purchase (user_id, created_at DESC);
CREATE INDEX ix_purchase_time ON purchase (created_at);

CREATE TABLE entitlement (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             uuid NOT NULL REFERENCES app_user(id),
    purchase_id         uuid UNIQUE REFERENCES purchase(id),   -- NULL when granted by an admin
    type                text NOT NULL CHECK (type IN ('PARTY_PASS', 'HOST_PASS')),
    starts_at           timestamptz NOT NULL,
    ends_at             timestamptz NOT NULL,
    monthly_game_limit  integer CHECK (monthly_game_limit IS NULL OR monthly_game_limit > 0),  -- NULL = unlimited
    granted_by_admin    boolean NOT NULL DEFAULT false,
    revoked_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at),
    CHECK (granted_by_admin OR purchase_id IS NOT NULL)
);
CREATE INDEX ix_entitlement_user ON entitlement (user_id, ends_at);

CREATE TABLE webhook_event (
    provider      text NOT NULL,
    event_id      text NOT NULL,
    event_type    text NOT NULL,
    payload       jsonb NOT NULL,
    received_at   timestamptz NOT NULL DEFAULT now(),
    processed_at  timestamptz,
    last_error    text,
    PRIMARY KEY (provider, event_id)
);
CREATE INDEX ix_webhook_unprocessed ON webhook_event (received_at) WHERE processed_at IS NULL;

-- One row per started game. Inserted when the first round starts (that is when a free game or a pass game
-- is spent), completed when the room finishes the game or closes. Player names and texts are never stored.
CREATE TABLE game_session (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    host_user_id         uuid NOT NULL REFERENCES app_user(id),
    previous_session_id  uuid REFERENCES game_session(id),     -- "play again" chain
    room_code            text NOT NULL,
    mode                 text NOT NULL CHECK (mode IN ('STANDARD', 'STREAMER')),
    tone                 text NOT NULL CHECK (tone IN ('FAMILY', 'CHEEKY', 'SPICY')),
    length               text NOT NULL CHECK (length IN ('SHORT', 'LONG')),
    language             text NOT NULL DEFAULT 'en',
    prompt_version       text NOT NULL,
    is_free              boolean NOT NULL,
    entitlement_id       uuid REFERENCES entitlement(id),
    player_count         smallint,
    audience_peak        integer,
    rounds_played        smallint,
    dossier_facts        smallint,
    started_at           timestamptz NOT NULL,
    ended_at             timestamptz,
    end_reason           text CHECK (end_reason IN ('COMPLETED', 'ENDED_BY_HOST', 'IDLE', 'MAX_AGE', 'NOT_ENOUGH_PLAYERS', 'SHUTDOWN')),
    CHECK (is_free = (entitlement_id IS NULL))
);
CREATE INDEX ix_game_host_time ON game_session (host_user_id, started_at);
CREATE INDEX ix_game_time ON game_session (started_at);

CREATE TABLE ai_call (
    id               bigserial PRIMARY KEY,
    game_session_id  uuid REFERENCES game_session(id),
    purpose          text NOT NULL CHECK (purpose IN ('ROUND_GEN', 'HOST_LINE', 'FINALE', 'MODERATION', 'TTS')),
    provider         text NOT NULL,
    model            text NOT NULL,
    prompt_version   text,
    input_tokens     integer,
    output_tokens    integer,
    tts_chars        integer,
    cost_micros      bigint NOT NULL CHECK (cost_micros >= 0),   -- millionths of a dollar, no floats
    latency_ms       integer NOT NULL,
    outcome          text NOT NULL CHECK (outcome IN ('OK', 'TIMEOUT', 'ERROR', 'INVALID_JSON', 'FALLBACK')),
    is_free_game     boolean NOT NULL DEFAULT false,              -- counts against the daily free budget
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_call_time ON ai_call (created_at);
CREATE INDEX ix_ai_call_game ON ai_call (game_session_id);

CREATE TABLE moderation_event (
    id               bigserial PRIMARY KEY,
    game_session_id  uuid REFERENCES game_session(id),
    stage            text NOT NULL CHECK (stage IN ('DOSSIER', 'INTAKE', 'ANSWER', 'AI_OUTPUT')),
    category         text NOT NULL,
    action           text NOT NULL CHECK (action IN ('BLOCKED', 'SKIPPED_BY_PLAYER', 'SKIPPED_BY_OWNER')),
    created_at       timestamptz NOT NULL DEFAULT now()
);   -- the text itself is deliberately not stored
CREATE INDEX ix_moderation_time ON moderation_event (created_at);

CREATE TABLE game_feedback (
    game_session_id  uuid PRIMARY KEY REFERENCES game_session(id),
    rating           smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment          text CHECK (length(comment) <= 500),
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app_setting (
    key         text PRIMARY KEY,
    value       jsonb NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO app_setting (key, value) VALUES
    ('free_games_enabled', 'true'),
    ('tts_enabled', 'true'),
    ('daily_free_ai_budget_micros', '50000000'),
    ('audience_cap', '2000'),
    ('drain_mode', 'false');
