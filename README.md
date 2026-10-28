# OpenRAC Server

The multiplayer backend for OpenRAC. Each game keeps one TCP connection to this server. The server
links players into parties and forwards their traffic. The host's game is authoritative, and the
server never reads the game data it forwards.

In its current state it serves only Ratchet & Clank 1 multiplayer mods (and possibly Ratchet &
Clank 2). The same backend is meant to serve the Ratchet & Clank 3 and 4 decompilations later.

## Layout

| Module | Contents |
|---|---|
| `services/common` | Packet ids and opcodes, buffers, packets, codecs and the pipeline, shared by server and clients |
| `services/server` | The running server: sessions, parties and the packet handler |

Coding rules: [docs/CODING_CONVENTIONS.md](docs/CODING_CONVENTIONS.md).

## Build, test and run

Java 25 is downloaded by Gradle if it is not installed.

```sh
./gradlew build                  # compile and run the tests
./gradlew :server:run            # listen on port 7770
./gradlew :server:run --args=9000
OPENRAC_PORT=9000 ./gradlew :server:run
```

For a deployment, build the one-jar server and run it with the script:

```sh
./gradlew :server:buildServerJar   # services/server/build/libs/openrac-server-1.0.0.jar
./scripts/run_server.sh            # or ./scripts/run_server.sh 9000
```

Ctrl+C (or SIGTERM) closes the port and shuts the event loops down.

Logging is coloured and levelled. `DEBUG_LEVEL` picks what shows (`all`, the default, `verbose`,
`warning`, `error` or `none`); `DEBUGGING=true` adds the debug lines (party chat), and with
`DEBUG_LEVEL=verbose` also every game data packet.

## Limits

Each connection has a rate per packet kind: a short burst, then a steady rate (chat 1 a second,
party requests 1 a second, game data 60 a second, clock requests 2 a second; see `Constants`).
A packet over its rate is dropped and counts as a strike; strikes are forgiven over time, and a
connection that keeps flooding is closed. One address may hold 8 connections, and a connection
that has not said hello within 10 seconds is closed.

## Wire format

Every packet is a frame: a 4-byte big-endian length, then the packet id byte, then an opcode byte
for packets that have one, then the payload. Frames are at most 65,536 bytes, length included.
Integers are big-endian; a string is a 2-byte length then UTF-8 bytes (-1 for empty).

The first packet must be `HELLO`. Anything malformed, unknown or oversized closes the connection.
Names and versions are 1 to 32 characters, chat messages 1 to 256. Parties hold 4 players.

| Id | Packet | Direction | Payload |
|---|---|---|---|
| 0 | `HELLO` | client → server | version string, display name string |
| | | server → client | session id int |
| 1 | `PARTY` | both | opcode byte, then as below |
| 2 | `CHAT` | client → server | message string |
| | | server → members | sender id int, message string |
| 3 | `DATA` | client → server | target id int, opaque bytes to the end of the frame |
| | | server → client | sender id int, opaque bytes |
| 4 | `ERROR` | server → client | reason opcode byte |
| 5 | `TIME` | client → server | client time long (ms) |
| | | server → client | the client time echoed, server time long (ms since the server started) |

`PARTY` opcodes:

| Opcode | Name | Direction | Payload |
|---|---|---|---|
| 0 | `CREATE` | client → server | none; answered with `INFO`, the creator hosts a private party (joined by code only) |
| 1 | `JOIN` | client → server | join code string (6 characters, any case) |
| 2 | `LEAVE` | client → server | none |
| 3 | `INFO` | server → client | code string, host id int, member count byte, then id int + name string per member |
| 4 | `JOINED` | server → members | id int, name string |
| 5 | `LEFT` | server → members | id int |
| 6 | `END` | server → members | none; the host left, so the party is gone |
| 7 | `FIND` | client → server | none; joins the oldest open party with room, else hosts a new open party; answered with `INFO` |

`ERROR` reasons: 0 `PARTY_NOT_FOUND`, 1 `PARTY_FULL`, 2 `ALREADY_IN_PARTY`, 3 `NOT_IN_PARTY`.

`DATA` routing: from a member, the server sends it to the host and ignores the target. From the
host, target 0 sends it to every other member, and a session id sends it to that member only.
Session ids start at 1.

`TIME` lets each game stamp its data against one clock: from its send time, the echo and the
server's time, a client estimates the round trip and its offset to the server's clock.

## License

GNU Affero General Public License v3.0; see [LICENSE](LICENSE).
