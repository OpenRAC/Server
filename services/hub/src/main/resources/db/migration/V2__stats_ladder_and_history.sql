-- Finished games as game servers report them, newest read first.
CREATE TABLE games (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    family           TEXT NOT NULL,
    region           TEXT NOT NULL,
    server           TEXT NOT NULL,
    name             TEXT NOT NULL,
    mode             INT NOT NULL,
    map              INT NOT NULL,
    duration_seconds INT NOT NULL,
    winning_team     INT NOT NULL,
    ended_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX games_family_ended ON games (family, ended_at DESC);

-- Each game's scoreboard. A guest, or an account since deleted, keeps its line with no account.
CREATE TABLE game_players (
    game_id    BIGINT NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    slot       SMALLINT NOT NULL,
    account_id BIGINT REFERENCES accounts (id) ON DELETE SET NULL,
    name       TEXT NOT NULL,
    team       INT NOT NULL,
    kills      INT NOT NULL,
    deaths     INT NOT NULL,
    score      INT NOT NULL,
    won        BOOLEAN NOT NULL,
    PRIMARY KEY (game_id, slot)
);

CREATE INDEX game_players_account ON game_players (account_id);

-- Each account's totals per game family, added to with every reported game.
CREATE TABLE player_stats (
    account_id BIGINT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    family     TEXT NOT NULL,
    games      INT NOT NULL DEFAULT 0,
    wins       INT NOT NULL DEFAULT 0,
    kills      INT NOT NULL DEFAULT 0,
    deaths     INT NOT NULL DEFAULT 0,
    score      BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, family)
);

-- The game's own ladder values (up to 100 per row), which it writes itself and sorts by any one.
CREATE TABLE ladder_stats (
    account_id  BIGINT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    family      TEXT NOT NULL,
    ladder_type INT NOT NULL,
    stats       INT[] NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, family, ladder_type)
);

CREATE INDEX ladder_stats_ladder ON ladder_stats (family, ladder_type);

-- The game's career bytes (up to 256), opaque to the hub, shown with the account.
CREATE TABLE career_stats (
    account_id BIGINT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    family     TEXT NOT NULL,
    stats      BYTEA NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, family)
);
