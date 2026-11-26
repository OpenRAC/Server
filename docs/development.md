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
| `:common` | `PacketCodecTests`, `RateLimitTests`, `RegionsTests`, `limits/ConnectionLimitTests`, `system/EnvironmentTests`; the shared embedded peer is in `src/testFixtures` (`EmbeddedPeer`) |
| `:hub` | `HubFlowTests`, `MemoryStoreTests`, `PostgresStoreTests`; both store tests run `StoreContract`, so the memory stores behave as PostgreSQL's |
| `:server` | `DatagramFlowTests`, `HubLinkTests`, `NetworkFlowTests`, `PartyFlowTests`, `ResumeFlowTests`, `RoomFlowTests`, `UiFlowTests`, `http/ModSetFlowTests` |

Most tests run a service's real pipeline on Netty's `EmbeddedChannel`. `NetworkFlowTests` starts a
hub and a game server on real loopback sockets instead, linked as a region is, and takes a few
seconds for the heartbeat.

## Checks

Three scripts in `scripts/` check what the build does not. Each needs only Python 3:

```sh
python3 scripts/check_conventions.py --all                  # every tracked file
python3 scripts/check_conventions.py --base origin/master    # the files this branch changed
python3 scripts/check_links.py                              # links and anchors in README.md and docs/
python3 scripts/smoke_test.py                               # against a running docker compose up
```

`check_conventions.py` counts the rules of [CODING_CONVENTIONS.md](CODING_CONVENTIONS.md) that a
script can: `var` declarations, single statements in braces, `//` comments over 12 words or three
lines tall, end-of-line comments anywhere but on fields and constants, classes and public or
protected methods without JavaDoc, `import static`, and em dashes in any file (and in commit
messages, with `--commits BASE..HEAD`). Test methods and `@Nested` test classes carry a
`@DisplayName` instead of JavaDoc. The rest of the self-check (a comment per branch, comments that
restate their line, member order, reuse, wire values in `:common`) still needs a reader.

`smoke_test.py` plays a client's first steps against a running hub and game server: `HELLO` and
`ACCOUNT LOGIN` at the hub, `REGIONS` until the game server is listed, then `HELLO` and
`ACCOUNT TOKEN` at the game server.

## CI and releases

GitHub Actions runs `.github/workflows/ci.yml` on every push and pull request:

| Job | What it does |
|---|---|
| Build and test | `./gradlew build` on JDK 25, against a `postgres:18` service so `PostgresStoreTests` runs (and fails the job if it skipped); the test reports are uploaded when it fails |
| Coding conventions | `check_conventions.py` on the files and commit messages since the base, then on every file |
| Docs links | `check_links.py` |
| Docker image | builds the `Dockerfile` |
| Compose smoke test | `docker compose up` (PostgreSQL, the hub, a game server), then `smoke_test.py`, printing the services' logs |

To run the PostgreSQL tests the way CI does:

```sh
docker run -d --rm --name openrac-test-db -p 5432:5432 -e POSTGRES_DB=openrac_test \
    -e POSTGRES_USER=openrac -e POSTGRES_PASSWORD=openrac postgres:18
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/openrac_test TEST_DATABASE_PASSWORD=openrac ./gradlew :hub:test
```

Pushing a version tag (`git tag v1.2.0 && git push origin v1.2.0`) runs
`.github/workflows/release.yml`: it builds and tests, attaches `openrac-hub-1.2.0.jar`,
`openrac-server-1.2.0.jar` and their `SHA256SUMS` to a GitHub release, and pushes the image to
`ghcr.io/openrac/server` as `v1.2.0` and `latest`. Nothing is deployed: the servers are updated by
whoever runs them.

## Layout

```
build-logic/         Gradle convention plugin (openrac.java-conventions)
gradle/              the wrapper and libs.versions.toml, the one list of dependency versions
scripts/             run_hub.sh and run_server.sh, which run the built jars, and the checks
.github/workflows/   ci.yml (every push) and release.yml (version tags)
services/common/     :common, package com.openrac
  net/                 NetworkConstants, codecs, the Netty pipeline, limits, datagrams
  net/packets/         Packets, Opcodes, buffers and the GameResult record
  net/packets/model/   the buffers, GameResult and the IncomingPacket base
  net/packets/out/     one class per outgoing packet
  system/, utils/      Environment (.env loading), the clock, the logger
services/hub/        :hub, package com.openrac.hub
  accounts/            accounts and sessions, memory and PostgreSQL stores
  database/            the connection pool and Flyway
  servers/             the registered game servers and the live list
  stats/               totals, ladders and career, memory and PostgreSQL stores
  net/                 the hub's connection and packet handler
  net/packets/         PacketRouter, and in/: one class per packet the hub takes
  resources/db/migration/  the Flyway migrations
services/server/     :server, package com.openrac.server
  party/               parties and their controller
  hub/                 the link to the hub, and MODE
  net/                 the connection, the TCP packet handler and the datagram handler
  net/packets/         PacketRouter, game/in/ (players') and hub/in/ (the hub's): one class per packet taken
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
3. Add an `Incoming<Name>Packet` for the side that takes it (`net/packets/game/in/` or
   `net/packets/hub/in/` on a game server, `net/packets/in/` on the hub) and register it in that
   side's `PacketRouter`. Say whether it is strict and give it a rate limit if a client can send it
   in a loop (see [limits.md](limits.md)). An opcode switch lives in that class, one private method
   per opcode.
4. If shipped clients or servers would close on it, gate it: a new feature bit in
   `NetworkConstants` that the sender offers in `UDP`, or a new client bit in `HELLO`.
5. Add a flow test beside the others.
6. Document it in [protocol.md](protocol.md), and its behaviour in the topic file it belongs to.
