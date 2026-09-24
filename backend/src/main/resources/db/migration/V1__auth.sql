-- Hosts, passwordless sign-in challenges and server-side sessions.

CREATE TABLE app_user (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email          text NOT NULL,
    display_name   text,
    google_sub     text UNIQUE,
    role           text NOT NULL DEFAULT 'HOST' CHECK (role IN ('HOST', 'ADMIN')),
    country        char(2),
    created_at     timestamptz NOT NULL DEFAULT now(),
    last_login_at  timestamptz,
    deleted_at     timestamptz
);
CREATE UNIQUE INDEX ux_user_email ON app_user (lower(email));

-- One email carries a one-time link and a 6-digit code; only hashes are stored.
CREATE TABLE magic_link_token (
    token_hash  bytea PRIMARY KEY,
    email       text NOT NULL,
    code_hash   bytea NOT NULL,
    attempts    smallint NOT NULL DEFAULT 0,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_ip  inet
);
CREATE INDEX ix_magic_link_email ON magic_link_token (lower(email), created_at DESC);

-- Standard Spring Session JDBC schema for PostgreSQL.
CREATE TABLE spring_session (
    primary_id             char(36) NOT NULL,
    session_id             char(36) NOT NULL,
    creation_time          bigint NOT NULL,
    last_access_time       bigint NOT NULL,
    max_inactive_interval  int NOT NULL,
    expiry_time            bigint NOT NULL,
    principal_name         varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id  char(36) NOT NULL,
    attribute_name      varchar(200) NOT NULL,
    attribute_bytes     bytea NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
