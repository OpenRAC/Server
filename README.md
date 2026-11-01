# OpenRAC Server

The multiplayer backend for OpenRAC. Each game keeps one TCP connection to this server, and may add
UDP on the same port for game state (see [UDP](#udp)). The server links players into parties and
forwards their traffic. The host's game is authoritative, and the server never reads the game data
it forwards.

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
./gradlew build                          # compile and run the tests
./gradlew :server:run                    # listen on 0.0.0.0:7770
./gradlew :server:run --args="--port 9000 --http-port 9001"
```

For a deployment, build the one-jar server and run it with the script:

```sh
./gradlew :server:buildServerJar         # services/server/build/libs/openrac-server-1.0.0.jar
./scripts/run_server.sh                  # or ./scripts/run_server.sh --port 9000
```

Ctrl+C (or SIGTERM) closes the port and shuts the event loops down.

### Docker

The image builds the server with the JDK and runs it on the JRE alone, as a non-root user. Only
Docker is needed: no Java or Gradle on the host.

```sh
docker compose up -d                     # build and start it: 7770 (TCP and UDP), 7771 (TCP)
docker compose logs -f                   # follow the log
docker compose down                      # stop it
```

Or without Compose:

```sh
docker build -t openrac-server .
docker run -d --name openrac-server -p 7770:7770/tcp -p 7770:7770/udp -p 7771:7771/tcp \
    -v openrac-modsets:/app/data openrac-server
```

Both TCP and UDP must be published: game state uses UDP where it gets through. Port 7771 serves the
launcher's mod sets, and the `/app/data` volume keeps them across rebuilds. A `.env` beside
`compose.yaml` overrides `.env.defaults` (as do `-e KEY=value` and trailing `--key value`
arguments with `docker run`). To listen on another port, change the published one
(`-p 9000:7770/tcp -p 9000:7770/udp`) rather than `PORT`, which is the port inside the container.

## Configuration

Settings live in `.env.defaults` at the repository root: the host and port, logging, the party
size and the limits below. Put local changes in a `.env` beside it (ignored by git); it overrides
the defaults, and command-line pairs (`--port 9000`, `--max-party-size 2`) override both.

Logging is coloured and levelled. `DEBUG_LEVEL` picks what shows (`all`, the default, `verbose`,
`warning`, `error` or `none`); `DEBUGGING=true` adds the debug lines (party chat, dropped
datagrams), and with `DEBUG_LEVEL=verbose` also every game data packet and UDP state datagram.

## Limits

Each connection has a rate per packet kind: a short burst, then a steady rate (chat 1 a second,
party requests (including `LIST`) 1 a second, game data 60 a second, clock requests 2 a second; see `.env.defaults`).
A packet over its rate is dropped and counts as a strike; strikes are forgiven over time, and a
connection that keeps flooding is closed. One address may hold 8 connections, and a connection
that has not said hello within 10 seconds is closed.

UDP `STATE` has the same rate per session as `DATA`. Over it, a datagram is dropped, never struck:
UDP never closes a session.

## Wire format

Every packet is a frame: a 4-byte big-endian length, then the packet id byte, then an opcode byte
for packets that have one, then the payload. Frames are at most 65,536 bytes, length included.
Integers are big-endian; a string is a 2-byte length then UTF-8 bytes (-1 for empty).

The first packet must be `HELLO`. Anything malformed, unknown or oversized closes the connection.
Names and versions are 1 to 32 characters, chat messages 1 to 256. Parties hold 4 players. A party description is at most 48 characters of printable ASCII, and a `LIST` answer carries at most 32 parties (`MAX_DESCRIPTION_LENGTH`, `MAX_LISTED_PARTIES`).

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
| 6 | `UDP` | server → client | UDP token long, UDP port int, feature bits byte, resume token long; sent right after the `HELLO` answer |

`PARTY` opcodes:

| Opcode | Name | Direction | Payload |
|---|---|---|---|
| 0 | `CREATE` | client → server | description string, optional (an empty body means none); answered with `INFO`, the creator hosts a party (match finding can fill it, its code invites) |
| 1 | `JOIN` | client → server | join code string (6 characters, any case) |
| 2 | `LEAVE` | client → server | none |
| 3 | `INFO` | server → client | code string, host id int, member count byte, then id int + name string per member, then description string; once either means something, the host's epoch int, then the count byte and ids int of the members away |
| 4 | `JOINED` | server → members | id int, name string |
| 5 | `LEFT` | server → members | id int |
| 6 | `END` | server → members | none; the host left, so the party is gone |
| 7 | `FIND` | client → server | none; joins the oldest party with room, else hosts a new one; answered with `INFO`; the party it creates has no description |
| 8 | `LIST` | client → server | none; allowed in or out of a party, answered with `LIST` |
| | | server → client | count byte (at most 32), then per party: code string, host name string, member count byte, max party size byte, description string |
| 9 | `HOST_CHANGED` | client → server | decline byte, count byte, then id int per member; from the host only (else `NOT_HOST`). 0: these may follow it, the first choice first, replacing the last list. 1: a new host says it cannot, and the next one is asked |
| | | server → members | new host id int, epoch int (one more at each change) |
| 10 | `RESUME` | client → server | party code string, session id int, resume token long; asked after `HELLO` on a new connection (below) |
| | | server → members | id int, byte: 0 its connection dropped and its slot waits, 1 it came back |
| 11 | `KICK` | client → server | member id int; from the host only (else `NOT_HOST`). The member gets `KICKED`, the others `LEFT`. Its connection stays and its code still works, but `FIND` no longer puts it in that party. An id that is not a member's does nothing |
| 12 | `KICKED` | server → member | none; the host removed it from the party |

The description is sanitised on the server: only printable ASCII (0x20 to 0x7E) is kept, the ends are
trimmed and it is cut to 48 characters. `LIST` names the parties with room (members below the party
size), oldest first, without the requester's own party; created and match-found parties both appear.
It shares the party-request rate, which allows a request every 3 seconds.

`ERROR` reasons: 0 `PARTY_NOT_FOUND`, 1 `PARTY_FULL`, 2 `ALREADY_IN_PARTY`, 3 `NOT_IN_PARTY`,
4 `NOT_HOST`, 5 `RESUME_REFUSED`.

`UDP`'s feature bits say which requests this server takes, since an unknown one closes the
connection: bit 0 (1) `KICK`, bit 1 (2) `RESUME`. A client from before them stops reading at the port.

A client that can come back says so with a byte after its name in `HELLO` (bit 0). Its connection
dropping is then no leaving: the others get `RESUME` with 0, and the session waits for
`RESUME_SECONDS` (120), keeping its id, its slot and its place in the party. On a new connection the
client says `HELLO`, then `RESUME`: refused with `RESUME_REFUSED`, or answered as if greeted again
(`HELLO` with the old id, `UDP`, `INFO`), the others told with `RESUME` 1. UDP is bound again from
nothing. In a party, a client that can come back and has been silent for `PARTY_IDLE_SECONDS` (5) on
both TCP and UDP is closed, since a machine that dies closes nothing. A client without that bit
leaves when its connection goes, as ever.

A host's connection dropping makes the party wait for `HOST_GRACE_SECONDS` (15): it can come back
as above, nobody can join or find the party meanwhile, and nothing is forwarded to it. Then the first
of the host's last `HOST_CHANGED` list who is there hosts, everyone is told with `HOST_CHANGED`, and
the old host stays as a member whose slot waits out the rest of its time: if it comes back, `INFO`
tells it the host and epoch of the party it finds. A host that leaves on purpose is followed at once.
A party whose host named nobody, or with nobody left to follow, ends with `END`.

`DATA` routing: from a member, the server sends it to the host and ignores the target. From the
host, target 0 sends it to every other member, and a session id sends it to that member only.
Session ids start at 1.

`TIME` lets each game stamp its data against one clock: from its send time, the echo and the
server's time, a client estimates the round trip and its offset to the server's clock.

## UDP

UDP is optional. Connecting, parties, chat, the clock and errors only ever use TCP, and so do game
events that must arrive (`DATA`). Game state, which the next update replaces anyway, may use UDP.
A client whose UDP is blocked never binds and keeps sending and receiving state as TCP `DATA`.

- **Token.** After answering `HELLO`, the server sends `UDP`: a random 64-bit token for the session
  and the port it takes datagrams on (the TCP port).
- **Binding.** Any valid datagram (the right session id and token) binds its sender's address to the
  session, and a later one from a new address rebinds it, as NAT may move the port. A client sends
  `BIND` every few seconds to keep its NAT mapping open; the server runs no timers for it.
- **Routing.** `STATE` follows the `DATA` rules: a member's goes to the host, the host's to target 0
  (every other member) or one member. Each recipient gets it by UDP once bound, otherwise as a TCP
  `DATA` packet from the sender. A session the server has heard no valid datagram from for
  `UDP_TIMEOUT_SECONDS` (15) gets state over TCP again until its next datagram.
- **Drops.** A datagram over 1,200 bytes, cut short, of an unknown kind, or naming an unknown session
  or the wrong token is dropped without an answer.

Datagrams have no length prefix. Integers are big-endian.

| Direction | Layout |
|---|---|
| client → server | session id int, token long, kind byte, then the kind's payload |
| server → client | kind byte, then the kind's payload |

| Kind | Name | Payload |
|---|---|---|
| 0 | `BIND` | client → server: none |
| 1 | `STATE` | client → server: target id int, opaque bytes to the end; server → client: sender id int, opaque bytes |

## Mod sets

A small HTTP side-port (`HTTP_PORT`, 7771, TCP) stores the launcher's mod sets behind short codes:
what friends type to get the same mods at the same versions. It has nothing to do with game traffic
and holds only public metadata. No login: a set names catalogue mods and their hashes, and the
launcher checks everything again and shows it to the player before anything changes.

| Route | Body | Answer |
|---|---|---|
| `PUT /v1/modsets` | the set, zlib-compressed JSON | `200` `{"code":"K7M29QXR"}`; `400` bad set; `413` too large; `429` over the rate |
| `GET /v1/modsets/<CODE>` | none | `200` the compressed set; `404` unknown or expired; `429` over the rate |

- **Codes.** 8 random characters of the party alphabet (40 bits), any case, with or without a dash.
  The same set gets the same code: the hash leaves out only its `created` date.
- **Life.** 30 days from the last read (`MODSET_TTL_DAYS`), so a party's code stays alive. Past 50,000
  sets (`MODSET_CAPACITY`) the set read longest ago goes.
- **Checks.** At most 16 KB compressed, 64 KB inflated and 40 mods in each list. The JSON is parsed
  strictly and may hold only the format's keys. `source.url` must be a `https://github.com/` repository,
  and no other value may hold an address, so the store cannot be used to host anything else.
- **Rates.** Per address: 10 stores and 60 fetches a minute, with the connection limit above. A
  connection serves one request and has 10 seconds. Behind a proxy every player shares its address.
- **Saved.** To `MODSET_FILE` every `MODSET_SAVE_MINUTES` and at shutdown, so a restart keeps the codes.
  A file that cannot be read is logged and the store starts empty; its codes then answer `404`.

## License

GNU Affero General Public License v3.0; see [LICENSE](LICENSE).
