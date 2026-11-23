# OpenRAC Server

[![CI](https://github.com/OpenRAC/Server/actions/workflows/ci.yml/badge.svg)](https://github.com/OpenRAC/Server/actions/workflows/ci.yml)
[![Release](https://github.com/OpenRAC/Server/actions/workflows/release.yml/badge.svg)](https://github.com/OpenRAC/Server/actions/workflows/release.yml)

The multiplayer backend for OpenRAC. A game first reaches the **hub** (`hub.openrac.dev`, port
7177), which keeps the accounts and stats and lists the **game servers** that registered with it,
one or more per region. The game then plays on the game server it picked, which links players into
parties and game rooms and forwards their traffic. The host's game is authoritative, and the server
never reads the game data it forwards. Anyone can run a hub, a game server or both.

In its current state it serves only Ratchet & Clank 1 multiplayer mods (and possibly Ratchet &
Clank 2). Ratchet & Clank 3's online play uses its game rooms (`RELAY`, `ADVERT`), and Ratchet &
Clank 4 is meant to follow.

How the pieces fit: [docs/architecture.md](docs/architecture.md).

## Modules

| Module | Path | Contents |
|---|---|---|
| `:common` | `services/common` | Packet ids and opcodes, buffers, packets, codecs and the pipeline, limits and the clock, shared by every service and the clients |
| `:hub` | `services/hub` | The hub: the game server list it keeps in step, accounts and sessions (PostgreSQL or memory), stats, the schema migrations |
| `:server` | `services/server` | A game server: sessions, parties, the packet handler, lobby screens, the mod set store and its link to the hub |

## Quick start

Java 25 is downloaded by Gradle if it is not installed.

```sh
./gradlew build                            # compile and run the tests
./gradlew :server:run --args="--mode all"  # a hub on 7177 and a game server on 7770, in memory
```

Or with only Docker installed, a whole network on one machine (PostgreSQL, the hub and one game
server):

```sh
docker compose up -d                       # 7177 (hub), 7770 TCP and UDP, 7771 (game server)
```

Set `DATABASE_PASSWORD` in a `.env` first. The other setups, the jars and the ports are in
[docs/self-hosting.md](docs/self-hosting.md).

## Documentation

| Document | What it covers |
|---|---|
| [Architecture](docs/architecture.md) | The hub, game servers and clients, and how a player gets into a game |
| [Protocol](docs/protocol.md) | Framing, every packet and opcode with its fields, datagrams, feature bits, error reasons |
| [Hub](docs/hub.md) | Registration, heartbeats, the server list, the addresses players are given |
| [Game server](docs/game-server.md) | Parties, game families, routing, adverts, resuming, host handover, UDP |
| [Accounts](docs/accounts.md) | Logins, session tokens, proving a login to a game server, login limits |
| [Stats](docs/stats.md) | Game results, totals, the game's own ladders and career bytes |
| [Lobby screens](docs/lobby-screens.md) | UI descriptions: how a host customises a game's multiplayer menus |
| [Mod sets](docs/mod-sets.md) | The HTTP side-port that stores the launcher's mod sets behind short codes |
| [Limits](docs/limits.md) | Rates, strikes and connection limits on the game server and the hub |
| [Database](docs/database.md) | PostgreSQL, Flyway migrations, the schema, the in-memory mode |
| [Configuration](docs/configuration.md) | Every setting in `.env.defaults`, with its default and meaning |
| [Self-hosting](docs/self-hosting.md) | The four setups, Docker, jars, ports to open |
| [Development](docs/development.md) | Building, testing, the checks, CI and releases, the source layout, adding a packet |
| [Coding conventions](docs/CODING_CONVENTIONS.md) | The rules every change follows |

The index with reading paths is [docs/README.md](docs/README.md).

## Acknowledgements

The protocol work drew on the Horizon Private Server project
(https://github.com/Horizon-Private-Server/horizon-server), read for reference only. No code was
taken from it.

## License

GNU Affero General Public License v3.0; see [LICENSE](LICENSE).
