# OpenRAC Server

The multiplayer backend for OpenRAC. A game first reaches the **hub** (`hub.openrac.dev`, port
7177), which keeps the accounts and lists the **game servers** that registered with it, one or more
per region. The game then keeps one TCP connection to the game server it picked, and may add UDP on
the same port for game state (see [UDP](#udp)). A game server links players into parties and
forwards their traffic. The host's game is authoritative, and the server never reads the game data
it forwards. Anyone can run a hub, a game server or both: see [Self-hosting](#self-hosting).

In its current state it serves only Ratchet & Clank 1 multiplayer mods (and possibly Ratchet &
Clank 2). Ratchet & Clank 3's online play uses its game rooms (`RELAY`, `ADVERT`), and Ratchet & Clank 4 is meant to follow.

## Layout

| Module | Contents |
|---|---|
| `services/common` | Packet ids and opcodes, buffers, packets, codecs and the pipeline, limits and the clock, shared by every service and the clients |
| `services/hub` | The hub: the game server list it keeps in step, accounts and sessions (PostgreSQL or memory), the schema migrations |
| `services/server` | A game server: sessions, parties, the packet handler, the mod set store and its link to the hub |

Coding rules: [docs/CODING_CONVENTIONS.md](docs/CODING_CONVENTIONS.md).

## Build, test and run

Java 25 is downloaded by Gradle if it is not installed.

```sh
./gradlew build                          # compile and run the tests
./gradlew :server:run --args="--mode all"  # a hub on 7177 and a game server on 7770, in memory
./gradlew :hub:run                       # only the hub, on 0.0.0.0:7177
./gradlew :server:run                    # only a game server on 7770, registering with hub.openrac.dev
```

For a deployment, build the one-jar services and run them with the scripts:

```sh
./gradlew :hub:buildHubJar               # services/hub/build/libs/openrac-hub-1.0.0.jar
./gradlew :server:buildServerJar         # services/server/build/libs/openrac-server-1.0.0.jar
./scripts/run_hub.sh                     # or ./scripts/run_hub.sh --hub-port 9177
./scripts/run_server.sh                  # or ./scripts/run_server.sh --port 9000
```

Ctrl+C (or SIGTERM) closes the ports and shuts the event loops down. A game server tells its hub
it is closing first, so players stop being sent to it.

The PostgreSQL tests run only against a database you name, and are skipped otherwise:

```sh
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/openrac_test TEST_DATABASE_PASSWORD=... ./gradlew :hub:test
```

### Docker

The image holds both services, built with the JDK and run on the JRE alone as a non-root user. Only
Docker is needed: no Java or Gradle on the host. `docker-compose.yml` runs a whole network on one
machine: PostgreSQL, the hub and one game server registered with it.

```sh
docker compose up -d                     # build and start: 7177 (hub), 7770 TCP and UDP, 7771 (game server)
docker compose logs -f                   # follow the logs
docker compose down                      # stop it (the volumes keep the accounts and mod sets)
```

Put a `.env` beside `docker-compose.yml` before the first start, with at least
`DATABASE_PASSWORD` (the PostgreSQL password, `openrac` otherwise) and, for players outside this
machine's network, `REGION_HOST` (the address they reach the game server on) and `REGION_NAME`.
It also overrides `.env.defaults` inside both containers.

Or without Compose, one service per container (the default command runs `server.jar`):

```sh
docker build -t openrac-server .
docker run -d --name openrac-server -p 7770:7770/tcp -p 7770:7770/udp -p 7771:7771/tcp \
    -v openrac-modsets:/app/data openrac-server
docker run -d --name openrac-hub -p 7177:7177/tcp openrac-server hub.jar
```

Both TCP and UDP must be published for a game server: game state uses UDP where it gets through.
Port 7771 serves the launcher's mod sets, and the `/app/data` volume keeps them across rebuilds. To
listen on another port, set `PORT` and publish the same number on both sides
(`-e PORT=9000 -p 9000:9000/tcp -p 9000:9000/udp`): a game server tells the hub its port, so the
two must match.

## Configuration

Settings live in `.env.defaults` at the repository root: the host and ports, the hub and its
database, logging, the party size and the limits below. The hub and the game server read the same
file, each the settings it knows. Put local changes in a `.env` beside it (ignored by git); it overrides
the defaults, and command-line pairs (`--port 9000`, `--max-party-size 2`) override both.

Logging is coloured and levelled. `DEBUG_LEVEL` picks what shows (`all`, the default, `verbose`,
`warning`, `error` or `none`); `DEBUGGING=true` adds the debug lines (party chat, dropped
datagrams), and with `DEBUG_LEVEL=verbose` also every game data packet and UDP state datagram.

## Limits

Each connection has a rate per packet kind: a short burst, then a steady rate (chat 1 a second,
party requests (including `LIST` and `ADVERT`) 1 a second, game data 60 a second, relayed data 120 a second, clock requests 2 a second; see `.env.defaults`).
A packet over its rate is dropped and counts as a strike; strikes are forgiven over time, and a
connection that keeps flooding is closed. One address may hold 8 connections, and a connection
that has not said hello within 10 seconds is closed.

The hub keeps the same per-address connection limit and hello deadline. A client gets 10 requests
in a burst, then 2 a second (`HUB_REQUEST_BURST`, `HUB_REQUEST_PER_SECOND`); past them requests are
dropped. Game servers are not rate limited, but a registration must come first and only once.

UDP `STATE` has the same rate per session as `DATA`. Over it, a datagram is dropped, never struck:
UDP never closes a session.

## Wire format

Every packet is a frame: a 4-byte big-endian length, then the packet id byte, then an opcode byte
for packets that have one, then the payload. Frames are at most 65,536 bytes, length included.
Integers are big-endian; a string is a 2-byte length then UTF-8 bytes (-1 for empty).

The first packet must be `HELLO`. Anything malformed, unknown or oversized closes the connection.
Names and versions are 1 to 32 characters, chat messages 1 to 256. Parties hold 4 players, or what `FAMILY_PARTY_SIZES` gives their family, up to 24 (Up Your Arsenal's: 8, what the unmodified game shows). A party description is at most 48 characters of printable ASCII, and a `LIST` answer carries at most 32 parties (`MAX_DESCRIPTION_LENGTH`, `MAX_LISTED_PARTIES`).

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
| 7 | `RELAY` | client → server | target id int (0 every other member), opaque bytes to the end of the frame |
| | | server → client | sender id int, opaque bytes |
| 8 | `REGIONS` | client → hub | the game family string |
| | | hub → client | count byte, then per game server: region name string, host string (empty: the address the client reached the hub on), port int, players int |
| 9 | `REGISTER` | game server → hub | key string, region name string, host string (empty: see [The hub](#the-hub)), port int, players int, server name string; the first packet on the link, instead of `HELLO` |
| 10 | `UI` | | claimed by the UI descriptions (Server PR #4) |
| 11 | `HUB` | both | opcode byte, then as below: a game server's link to the hub |
| 12 | `SERVERS` | both | opcode byte, then as below: the live game server list |
| 13 | `ACCOUNT` | both | opcode byte, then as below: logins at the hub, and proving them to a game server |

`PARTY` opcodes:

| Opcode | Name | Direction | Payload |
|---|---|---|---|
| 0 | `CREATE` | client → server | description string, optional (an empty body means none), then the game family string, optional; answered with `INFO`, the creator hosts a party (match finding can fill it, its code invites) |
| 1 | `JOIN` | client → server | join code string (6 characters, any case), then the game family string, optional |
| 2 | `LEAVE` | client → server | none |
| 3 | `INFO` | server → client | code string, host id int, member count byte, then id int + name string per member, then description string; once either means something, the host's epoch int, then the count byte and ids int of the members away |
| 4 | `JOINED` | server → members | id int, name string |
| 5 | `LEFT` | server → members | id int |
| 6 | `END` | server → members | none; the host left, so the party is gone |
| 7 | `FIND` | client → server | the game family string, optional; joins the oldest party of that family with room, else hosts a new one; answered with `INFO`; the party it creates has no description |
| 8 | `LIST` | client → server | the game family string, optional, then a flags byte, optional (bit 0: with adverts); allowed in or out of a party, answered with `LIST` |
| | | server → client | count byte (at most 32), then per party: code string, host name string, member count byte, max party size byte, description string; with adverts, the advert's length short and its bytes |
| 9 | `HOST_CHANGED` | client → server | decline byte, count byte, then id int per member; from the host only (else `NOT_HOST`). 0: these may follow it, the first choice first, replacing the last list. 1: a new host says it cannot, and the next one is asked |
| | | server → members | new host id int, epoch int (one more at each change) |
| 10 | `RESUME` | client → server | party code string, session id int, resume token long; asked after `HELLO` on a new connection (below) |
| | | server → members | id int, byte: 0 its connection dropped and its slot waits, 1 it came back |
| 11 | `KICK` | client → server | member id int; from the host only (else `NOT_HOST`). The member gets `KICKED`, the others `LEFT`. Its connection stays and its code still works, but `FIND` no longer puts it in that party. An id that is not a member's does nothing |
| 12 | `KICKED` | server → member | none; the host removed it from the party |
| 13 | `ADVERT` | client → server | opaque bytes to the end of the frame, at most `MAX_ADVERT_LENGTH` (512); from the host only (else `NOT_HOST`, or `NOT_IN_PARTY` outside a party). Replaces the bytes `LIST` shows with the party when asked for adverts |
| 14 | `SLOTS` | server → members that read it | count byte, then id int + slot byte per member; sent after `INFO` and whenever a member joins or leaves, only to clients that set bit 1 (2) of `HELLO`'s byte. A member keeps its slot while it is in the party; a newcomer takes the lowest free one, the host starts at 0 |

The description is sanitised on the server: only printable ASCII (0x20 to 0x7E) is kept, the ends are
trimmed and it is cut to 48 characters. `LIST` names the parties of the requester's game family with room
(members below the party size), oldest first, without the requester's own party; created and
match-found parties both appear.
It shares the party-request rate, which allows a request every 3 seconds.

`HUB` opcodes (between a game server and the hub):

| Opcode | Name | Direction | Payload |
|---|---|---|---|
| 0 | `REGISTERED` | hub → game server | the server's id in the list, int; the answer to `REGISTER` |
| 1 | `STATUS` | game server → hub | players int, parties int; the heartbeat, every `REGION_REPORT_SECONDS` (5) |
| 2 | `CLOSING` | game server → hub | none; the server is shutting down and leaves the list at once |
| 3 | `VERIFY` | game server → hub | request int, session token string; whose login a player's token is |
| 4 | `VERIFIED` | hub → game server | request int, account id long (0: no such session), account name string |

`SERVERS` opcodes (between a client and the hub):

| Opcode | Name | Direction | Payload |
|---|---|---|---|
| 0 | `WATCH` | client → hub | the game family string; answered with `ADDED` for every listed server, then each change as it happens |
| 1 | `ADDED` | hub → client | id int, region string, server name string, host string (empty: the hub's address), port int, players int, parties int |
| 2 | `CHANGED` | hub → client | id int, players int, parties int; only when a count changed |
| 3 | `REMOVED` | hub → client | id int; the server closed, said `CLOSING` or went quiet |

`ACCOUNT` opcodes:

| Opcode | Name | Direction | Payload |
|---|---|---|---|
| 0 | `LOGIN` | client → hub | name string, password string; a name nobody has registers with this password |
| 1 | `LOGGED_IN` | hub → client | account id long, name string (as registered), session token string, created byte (1: this login registered it) |
| | | game server → client | account id long, name string, empty token, 0; the answer to `TOKEN` |
| 2 | `TOKEN` | client → game server | the session token from the hub's `LOGGED_IN` |
| 3 | `LOGOUT` | client → hub | the session token; it stops working |

`ERROR` reasons: 0 `PARTY_NOT_FOUND`, 1 `PARTY_FULL`, 2 `ALREADY_IN_PARTY`, 3 `NOT_IN_PARTY`,
4 `NOT_HOST`, 5 `RESUME_REFUSED`, 6 `OTHER_GAME` (followed by the party's game family string),
7 `REGION_KEY` (the hub asks for another key; the server stops trying), 8 `HUB_FULL` (the hub
lists 32 servers; the server tries again), 9 `WRONG_PASSWORD`, 10 `INVALID_ACCOUNT` (a name or
password the hub does not take), 11 `SESSION_INVALID` (an unknown, expired or ended token),
12 `TRY_LATER` (too many wrong passwords from the address, the hub unreachable, or its database down).

`UDP`'s feature bits say which requests this server takes, since an unknown one closes the
connection: bit 0 (1) `KICK`, bit 1 (2) `RESUME`, bit 2 (4) `ADVERT`, bit 3 (8) `RELAY`, bit 4 (16)
`REGIONS`, bit 6 (64) `SERVERS`. A client from before them stops reading at the port. Bit 5 (32) is
claimed by the UI descriptions. The hub sends a `UDP` offer too, with token and port 0 (it takes no
datagrams) and bits 16 and 64; a game server sends neither, since the list is the hub's.

A client that can come back says so with a byte after its name in `HELLO` (bit 0); bit 1 asks for `SLOTS`. Its connection
dropping is then no leaving: the others get `RESUME` with 0, and the session waits for
`RESUME_SECONDS` (120), keeping its id, its slot and its place in the party. On a new connection the
client says `HELLO`, then `RESUME`: refused with `RESUME_REFUSED`, or answered as if greeted again
(`HELLO` with the old id, `UDP`, `INFO`), the others told with `RESUME` 1. UDP is bound again from
nothing. In a party, a client that can come back and has been silent for `PARTY_IDLE_SECONDS` (5) on
both TCP and UDP is closed, since a machine that dies closes nothing. A client without that bit
leaves when its connection goes, as ever. A client that sets bit 2 promises `TIME` every few seconds:
silent for `KEEP_ALIVE_IDLE_SECONDS` (30, long enough for a map load), it is closed and leaves its
party as on a disconnect (a host's party then goes on as a host's leaving decides).

A host's connection dropping makes the party wait for `HOST_GRACE_SECONDS` (15): it can come back
as above, nobody can join or find the party meanwhile, and nothing is forwarded to it. Then the first
of the host's last `HOST_CHANGED` list who is there hosts, everyone is told with `HOST_CHANGED`, and
the old host stays as a member whose slot waits out the rest of its time: if it comes back, `INFO`
tells it the host and epoch of the party it finds. A host that leaves on purpose is followed at once.
A party whose host named nobody, or with nobody left to follow, ends with `END`.

`RELAY` routing: any member may send to any other. Target 0 sends it to every other member, a
session id to that member only. It is for games whose players all talk to each other (Up Your
Arsenal's game rooms), has its own rate (`RELAY_BURST`, `RELAY_PER_SECOND`) and never goes over UDP.

An advert is what a host lists its party with, beside the description: game settings a lobby
shows, opaque to the server. A party starts with none (length 0).

`DATA` routing: from a member, the server sends it to the host and ignores the target. From the
host, target 0 sends it to every other member, and a session id sends it to that member only.
Session ids start at 1.

`TIME` lets each game stamp its data against one clock: from its send time, the echo and the
server's time, a client estimates the round trip and its offset to the server's clock.

### Game families

Each party belongs to its host's game family: the game's `openrac_api.world_family`, such as
`rac1-60-1` (Ratchet & Clank 1, PAL and NTSC alike) or `rac2-pal-1` (each Going Commando disc is its
own for now). `CREATE`, `FIND`, `LIST` and `JOIN` end with the asker's family, after their other
fields, so shipped clients, which stop before it, still work: a request without one counts as
`rac1-60-1` (`DEFAULT_FAMILY`), since every client from before families is a Ratchet & Clank 1 game.
A family is up to 32 characters of printable ASCII without spaces; anything else closes the connection.

`LIST` and `FIND` see only the asker's family. A `JOIN` by code into another family's party is
refused: with `OTHER_GAME` and the party's family when the client named its own, so the game can
say which game it is for, and with `PARTY_NOT_FOUND` when it did not, as an older client knows no
other reason.

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

## The hub

Players first reach the **hub** (what a game's `OPENRAC_SERVER` names: `hub.openrac.dev:7177`), then
pick a **game server** from its list, which runs the lobbies and game rooms (parties, adverts,
relays). It follows the online games' own split: an authentication server with a list of lobby
servers, then the lobby and the games of the one chosen. Up Your Arsenal's login maps onto the hub's
accounts, and its city select onto the list (one city per listed server, named by its region).

The connection flow:

1. **A game server registers.** It connects to `HUB_ADDRESS` and sends `REGISTER`: the hub's key,
   its region and its own name, where players reach it and its game port. The hub answers
   `REGISTERED` with the server's id, or an `ERROR` (`REGION_KEY`, `HUB_FULL`) and closes.
2. **It keeps in step.** Every `REGION_REPORT_SECONDS` (5) it sends `HUB STATUS` with its player and
   party counts. A server the hub has not heard from for `SERVER_TIMEOUT_SECONDS` (20) is dropped,
   as is one whose link closes or that says `CLOSING`. A server whose link drops connects again,
   one attempt at a time, and registers anew under a new id.
3. **A player reaches the hub.** Its client says `HELLO`, then `ACCOUNT LOGIN`. It asks `REGIONS` for
   a snapshot of the list, or `SERVERS WATCH` to get every server and then each change as it
   happens (`ADDED`, `CHANGED`, `REMOVED`).
4. **It picks a game server** and connects there: `HELLO` as before, then `ACCOUNT TOKEN` with its
   session token. The game server asks the hub (`HUB VERIFY`) and answers `LOGGED_IN` with the
   account, or `SESSION_INVALID`. A player that sends no token plays under its `HELLO` name, as
   before accounts.

Where players reach a game server: its `REGION_HOST` when set. Empty, the hub lists the address the
server connected from, except when that is the hub's own machine or a private network (loopback,
LAN, link-local, IPv6 ULA): players then get the hub's address, the one they already reached. A
server on another LAN machine than its hub sets `REGION_HOST`.

`MODE` picks a game server's role:

| Mode | Runs |
| --- | --- |
| `region` (default) | lobbies and game rooms, registered with the hub at `HUB_ADDRESS` (`hub.openrac.dev:7177`) |
| `all` | the same, plus a hub on `HUB_PORT` (7177) in the same process, which the server registers with over loopback: one box for a LAN party or a group of friends |

The hub alone runs from its own jar (`./scripts/run_hub.sh`, or `hub.jar` in the image).

### Accounts

The game's flow is kept: a name nobody has registers on its first login, and later logins must give
its password. Names are 1 to 16 printable ASCII characters with no space at either end, unique in
any case; passwords are 4 (`MIN_PASSWORD_LENGTH`) to 32 characters. Passwords are stored only as
bcrypt hashes (`PASSWORD_COST`, 10). A login hands out a random 256-bit session token, valid for
`SESSION_DAYS` (30) or until `LOGOUT`; the hub stores only its SHA-256. An address that sends
`LOGIN_FAILURES` (10) wrong passwords waits out `LOGIN_WINDOW_SECONDS` (600) with `TRY_LATER`.

Only the hub talks to the database: game servers learn who a player is through `VERIFY`, so they
need no database credentials.

### Database

With `DATABASE_URL` set (`jdbc:postgresql://host:5432/openrac`, with `DATABASE_USER` and
`DATABASE_PASSWORD`), the hub keeps the accounts in PostgreSQL. On start it brings the schema up to
date with the Flyway migrations in `services/hub/src/main/resources/db/migration`; a migration that
has run is never edited, a change is a new `V<n>__<what>.sql`. Without `DATABASE_URL` the accounts
live in memory, which suits a LAN party, and are gone when the hub stops.

The schema (`V1__accounts_and_sessions.sql`):

| Table | Columns |
|---|---|
| `accounts` | `id` bigint identity primary key, `name` text (unique on `lower(name)`), `password_hash` text, `created_at` timestamptz, `last_login` timestamptz |
| `sessions` | `token_hash` text primary key (SHA-256 of the token), `account_id` bigint references `accounts` on delete cascade, `created_at`, `expires_at` timestamptz |

Player stats, the ladder and game history come next, then clans and buddy or ignore lists: each as
its own migration, keyed on `accounts.id`. Game servers will report finished games to the hub over
`HUB`, so they still need no database.

## Self-hosting

The server is open source, and anyone can run any part of it. Pick the setup that fits:

- **One box for friends or a LAN party.** One process runs the hub and a game server:
  `./scripts/run_server.sh --mode all` (or `MODE=all` in a `.env`). Open TCP 7177, and TCP and UDP
  7770. Players point their game at your address, port 7177. Accounts live in memory unless you set
  `DATABASE_URL`.
- **A whole network with Docker.** `docker compose up -d` runs PostgreSQL, the hub and a game server
  (see [Docker](#docker)). Set `DATABASE_PASSWORD`, `REGION_NAME` and `REGION_HOST` in a `.env` first.
- **A game server on the official hub.** Run the game server alone (the default `MODE=region` and
  `HUB_ADDRESS`), with `REGION_NAME` naming your region and `REGION_HOST` your public address,
  plus `REGION_KEY` if the hub's operators gave you one. Open TCP and UDP 7770. It shows up in every
  player's list while it runs.
- **Your own hub with several game servers.** Run the hub (`./scripts/run_hub.sh`, with
  `DATABASE_URL` for lasting accounts and `REGION_KEY` to list only your servers), and point each game
  server's `HUB_ADDRESS` at it. Open TCP 7177 on the hub and 7770 (TCP and UDP) on each game server.

A game reaches a hub through the launcher's server setting (`OPENRAC_SERVER`, host and port).

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

## Acknowledgements

The protocol work drew on the Horizon Private Server project
(https://github.com/Horizon-Private-Server/horizon-server), read for reference only. No code was
taken from it.

## License

GNU Affero General Public License v3.0; see [LICENSE](LICENSE).
