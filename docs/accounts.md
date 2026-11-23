# Accounts

The hub keeps the accounts, in PostgreSQL or in memory (see [database.md](database.md)). The
packets are in [protocol.md](protocol.md#account-opcodes).

## Logging in

The game's flow is kept: a name nobody has registers on its first login (`ACCOUNT LOGIN`), and later
logins must give its password. The answer is `ACCOUNT LOGGED_IN`, whose created byte says whether
this login registered the name, or an `ERROR`:

- `WRONG_PASSWORD`: the account exists and the password is not its own.
- `INVALID_ACCOUNT`: a name or password the hub does not take.
- `TRY_LATER`: too many wrong passwords from the address, or the database is down.

Names are 1 to 16 printable ASCII characters with no space at either end, unique in any case;
passwords are 4 (`MIN_PASSWORD_LENGTH`) to 32 characters. Passwords are stored only as bcrypt hashes
(`PASSWORD_COST`, 10).

## Session tokens

A login hands out a random 256-bit session token, valid for `SESSION_DAYS` (30) or until
`ACCOUNT LOGOUT`; the hub stores only its SHA-256. A token longer than 64 characters closes the
connection.

## Proving a login to a game server

On a game server, the client sends `ACCOUNT TOKEN` with the token from the hub's `LOGGED_IN`. The
game server asks the hub (`HUB VERIFY`), and answers `ACCOUNT LOGGED_IN` with the account (and an
empty token) or `SESSION_INVALID`. When it has no link to the hub it answers `TRY_LATER`. A player
that sends no token plays under its `HELLO` name, as before accounts.

Game servers never see the database (see [database.md](database.md)).

## Limits

An address that sends `LOGIN_FAILURES` (10) wrong passwords waits out `LOGIN_WINDOW_SECONDS` (600)
with `TRY_LATER`. Hashing and database work runs on `ACCOUNT_THREADS` (4) threads. The hub's
request rates are in [limits.md](limits.md#the-hub).
