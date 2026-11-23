# Database

Only the hub talks to the database. Game servers learn who a player is through `HUB VERIFY` and
report finished games through `HUB RESULT`, so they need no database credentials.

## PostgreSQL

With `DATABASE_URL` set (`jdbc:postgresql://host:5432/openrac`, with `DATABASE_USER` and
`DATABASE_PASSWORD`), the hub keeps the accounts and stats in PostgreSQL, through a pool of
`DATABASE_POOL_SIZE` (8) connections. Docker Compose sets this up for you (see
[self-hosting.md](self-hosting.md#docker)).

## Migrations

On start the hub brings the schema up to date with the Flyway migrations in
`services/hub/src/main/resources/db/migration`. A migration that has run is never edited; a change
is a new `V<n>__<what>.sql`. Clans and buddy or ignore lists come later, each as its own migration
keyed on `accounts.id`.

## Schema

`V1__accounts_and_sessions.sql`, then `V2__stats_ladder_and_history.sql`:

| Table | Columns |
|---|---|
| `accounts` | `id` bigint identity primary key, `name` text (unique on `lower(name)`), `password_hash` text, `created_at` timestamptz, `last_login` timestamptz |
| `sessions` | `token_hash` text primary key (SHA-256 of the token), `account_id` bigint references `accounts` on delete cascade, `created_at`, `expires_at` timestamptz |
| `games` | `id` bigint identity primary key, `family`, `region`, `server`, `name` text, `mode`, `map`, `duration_seconds`, `winning_team` int, `ended_at` timestamptz; indexed on (`family`, `ended_at` desc) |
| `game_players` | (`game_id` references `games` on delete cascade, `slot` smallint) primary key, `account_id` bigint references `accounts` on delete set null (null: a guest), `name` text, `team`, `kills`, `deaths`, `score` int, `won` boolean |
| `player_stats` | (`account_id` references `accounts` on delete cascade, `family`) primary key, `games`, `wins`, `kills`, `deaths` int, `score` bigint, `updated_at`; added to with each `RESULT` |
| `ladder_stats` | (`account_id`, `family`, `ladder_type` int) primary key, `stats` int[] (up to 100), `updated_at`; the game's `LADDER_WRITE` rows |
| `career_stats` | (`account_id`, `family`) primary key, `stats` bytea (up to 256), `updated_at`; the game's `CAREER_WRITE` bytes |

What fills each table is in [accounts.md](accounts.md) and [stats.md](stats.md).

## In memory

Without `DATABASE_URL` the accounts and stats live in memory, which suits a LAN party, and are gone
when the hub stops (memory keeps the totals but not the game history).

## Testing against PostgreSQL

See [development.md](development.md#tests).
