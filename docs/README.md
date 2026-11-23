# OpenRAC Server documentation

One topic per file. Each fact lives in one file, and the others link to it.

## Start here

- [Architecture](architecture.md): the hub, game servers and clients, and the connection flow.
- [Self-hosting](self-hosting.md): running a hub, a game server or both.
- [Configuration](configuration.md): every setting and its default.

## The services

- [Hub](hub.md): registration, heartbeats and the live server list.
- [Accounts](accounts.md): logins and session tokens.
- [Stats](stats.md): game results, totals, ladders and career bytes.
- [Database](database.md): PostgreSQL, migrations, the schema and the in-memory mode.
- [Game server](game-server.md): parties, game families, routing, resuming, host handover and UDP.
- [Lobby screens](lobby-screens.md): server-described multiplayer menus.
- [Mod sets](mod-sets.md): the launcher's mod set codes over HTTP.
- [Limits](limits.md): rates, strikes and connection limits.

## The wire

- [Protocol](protocol.md): framing, packets, opcodes, datagrams, feature bits and error reasons.

## Working on the code

- [Development](development.md): building, testing, the checks, CI and releases, the layout and adding a packet.
- [Coding conventions](CODING_CONVENTIONS.md): the rules every change follows.
