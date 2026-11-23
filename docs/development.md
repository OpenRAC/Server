# Development

Every change follows [CODING_CONVENTIONS.md](CODING_CONVENTIONS.md).

## Build and run

Java 25 is downloaded by Gradle if it is not installed.

```sh
./gradlew build                            # compile and run the tests
./gradlew :server:run --args="--mode all"  # a hub on 7177 and a game server on 7770, in memory
./gradlew :hub:run                         # only the hub, on 0.0.0.0:7177
./gradlew :server:run                      # only a game server on 7770, registering with hub.openrac.dev
```

Jars and Docker are in [self-hosting.md](self-hosting.md).

## Tests

`./gradlew build` runs every test. The PostgreSQL tests run only against a database you name, and
are skipped otherwise (`TEST_DATABASE_USER` defaults to `openrac`):

```sh
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/openrac_test TEST_DATABASE_PASSWORD=... ./gradlew :hub:test
```

| Module | Tests |
|---|---|
| `:common` | `PacketCodecTests`, `RateLimitTests`; the shared embedded peer is in `src/testFixtures` (`EmbeddedPeer`) |
| `:hub` | `HubFlowTests`, `PostgresStoreTests` |
| `:server` | `DatagramFlowTests`, `HubLinkTests`, `PartyFlowTests`, `ResumeFlowTests`, `RoomFlowTests`, `UiFlowTests`, `http/ModSetFlowTests` |

## Layout

```
build-logic/         Gradle convention plugin (openrac.java-conventions)
gradle/              the wrapper and libs.versions.toml, the one list of dependency versions
scripts/             run_hub.sh and run_server.sh, which run the built jars
services/common/     :common, package com.openrac
  net/                 NetworkConstants, codecs, the Netty pipeline, limits, datagrams
  net/packets/         Packets, Opcodes, buffers and the GameResult record
  net/packets/out/     one class per outgoing packet
  system/, utils/      Environment (.env loading), the clock, the logger
services/hub/        :hub, package com.openrac.hub
  accounts/            accounts and sessions, memory and PostgreSQL stores
  database/            the connection pool and Flyway
  servers/             the registered game servers and the live list
  stats/               totals, ladders and career, memory and PostgreSQL stores
  net/                 the hub's packet handler
  resources/db/migration/  the Flyway migrations
services/server/     :server, package com.openrac.server
  party/               parties and their controller
  hub/                 the link to the hub, and MODE
  net/                 the TCP packet handler and the datagram handler
  ui/                  lobby screen descriptions
  http/                the mod set store
  resources/ui/        built-in UI descriptions
.env.defaults        every setting and its default
```

## Adding a packet

Ids and opcodes are ordinals, so new values are only ever appended (see
[protocol.md](protocol.md#append-only)).

1. Append the packet to `Packets`, or the opcode to its enum in `Opcodes` (a new opcode enum gets a
   `get(int)` like the others).
2. Add the outgoing class in `services/common/.../net/packets/out/`, extending `OutgoingPacket`.
3. Route it in the handler that takes it: `ServerPacketHandler` on a game server, `HubPacketHandler`
   on the hub. Give it a rate limit if a client can send it in a loop (see [limits.md](limits.md)).
4. If shipped clients or servers would close on it, gate it: a new feature bit in
   `NetworkConstants` that the sender offers in `UDP`, or a new client bit in `HELLO`.
5. Add a flow test beside the others.
6. Document it in [protocol.md](protocol.md), and its behaviour in the topic file it belongs to.
