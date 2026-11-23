-- Accounts players log in with. A name is unique in any case; the password is only ever a hash.
-- Later tables (stats, ladder, game history, clans, buddy and ignore lists) key on accounts.id.
CREATE TABLE accounts (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login    TIMESTAMPTZ
);

CREATE UNIQUE INDEX accounts_name_key ON accounts (lower(name));

-- A login's session. The hub hands the token to the player and keeps only its SHA-256.
CREATE TABLE sessions (
    token_hash TEXT PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX sessions_account ON sessions (account_id);
CREATE INDEX sessions_expires ON sessions (expires_at);
