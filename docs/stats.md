# Stats

Two kinds, both kept by the hub per game family. The packets are in
[protocol.md](protocol.md#stats-opcodes) (`STATS`) and [protocol.md](protocol.md#hub-opcodes)
(`HUB RESULT`); the tables are in [database.md](database.md#schema).

## Results and totals

**Totals** (games, wins, kills, deaths, score) come from the game servers: when a game ends, its
server sends `HUB RESULT` with the scoreboard (at most 32 players), and the hub stores the game (the
history) and adds each logged-in player's line to its totals. Guests get a line in the game but no
totals. A client reads an account's totals with `STATS TOTALS`; all are 0 before a game.

## Ladders and career

**The game's own stats** are what Up Your Arsenal writes and reads through Medius: its ladder rows
(up to 100 values per ladder type) and its career bytes (up to 256). The hub stores and sorts them
without reading them, so the port's lobby can answer the game's ladder screens directly. The
Medius request each opcode answers is in [protocol.md](protocol.md#stats-opcodes).

A ladder sorts by one of its values; ties share a position (the next row after two at 2 is 4), and
a row shorter than the index counts that value as 0. A page holds at most 32 rows.

Reads take any account id; writes need an `ACCOUNT LOGIN` on the same hub connection, and are
answered `NOT_LOGGED_IN` otherwise. A write replaces the account's own row or career bytes.

## In memory

Without a database, memory keeps the totals but not the game history, and everything is gone when
the hub stops. See [database.md](database.md#in-memory).
