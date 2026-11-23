# Configuration

Settings live in `.env.defaults` at the repository root. The hub and the game server read the same
file, each the settings it knows. Put local changes in a `.env` beside it (ignored by git); it
overrides the defaults, and command-line pairs override both: `--port 9000` sets `PORT`,
`--max-party-size 2` sets `MAX_PARTY_SIZE`. In the Docker image, `.env.defaults` is built in; mount
a `.env` at `/app/.env`, or pass `-e KEY=value` or `--key value`.

"Hub" below means the hub jar, or a game server in `MODE=all`.

## Connectivity and hosting

| Setting | Default | Read by | Meaning |
|---|---|---|---|
| `NAME` | `OpenRAC Server` | game server | The process's name in its own logs |
| `HOST` | `0.0.0.0` | both | The address to listen on (`0.0.0.0` for every interface) |
| `PORT` | `7770` | game server | The game port, TCP and UDP |
| `HTTP_PORT` | `7771` | game server | The [mod set](mod-sets.md) port, HTTP over TCP |

## Hub and regions

| Setting | Default | Read by | Meaning |
|---|---|---|---|
| `MODE` | `region` | game server | `region`: register with the hub at `HUB_ADDRESS`. `all`: also run that hub in the same process. See [architecture.md](architecture.md#modes) |
| `HUB_ADDRESS` | `hub.openrac.dev:7177` | game server | The hub to register with |
| `REGION` | `LOCAL` | game server | The region's code, from the [list below](#region-codes). An unknown code stops the server with an error. Clients turn it into a name ("United States") |
| `SERVER_NAME` | empty | game server | The name clients show with the region, such as `Thyrannosis USA`: up to 32 printable ASCII characters (spaces allowed). Empty: clients show the region alone. Anything else stops the server with an error |
| `REGION_NAME` | unset | game server | Deprecated, read for this release only: while `REGION` is `LOCAL`, a `REGION_NAME` holding a code stands in for it, with a warning. A free-text name (`Europe`) stops the server; set `REGION` instead |
| `REGION_HOST` | empty | game server | The address players reach this server on. Empty: see [hub.md](hub.md#addresses) |
| `REGION_REPORT_SECONDS` | `5` | game server | Seconds between heartbeats to the hub |
| `HUB_NAME` | `OpenRAC Hub` | hub | The hub's name in its logs |
| `HUB_PORT` | `7177` | hub | The hub's port, TCP |
| `REGION_KEY` | empty | both | On the hub, the key every server must send (empty: any server may register). On a game server, the key it sends |
| `SERVER_TIMEOUT_SECONDS` | `20` | hub | Silence after which a server is dropped from the list |
| `HUB_REQUEST_BURST` | `10` | hub | Client requests in a burst, before login |
| `HUB_REQUEST_PER_SECOND` | `2` | hub | The steady rate before login; over it the hub answers `TRY_LATER` |
| `HUB_ACCOUNT_REQUEST_BURST` | `40` | hub | Client requests in a burst, once logged in |
| `HUB_ACCOUNT_REQUEST_PER_SECOND` | `10` | hub | The steady rate once logged in |

### Region codes

A region is a code, never free text, so that every client can name it in its own words. The codes
are kept once, in `services/common` (`Regions`):

- every ISO 3166-1 alpha-2 country code, in upper case: `US` (United States), `CA` (Canada), `MX`,
  `BR`, `GB` (United Kingdom, not `UK`), `DE`, `FR`, `ES`, `IT`, `NL`, `SE`, `PL`, `RU`, `JP`,
  `KR`, `CN`, `SG`, `IN`, `AU`, `NZ`, `ZA` and the rest of the standard's 249;
- `EU`: Europe as a whole, for a server meant for the whole continent (ISO reserves the code);
- `LOCAL`: a LAN party or a development box, the default.

Codes read in any case (`us` is `US`). A code, once listed, is never removed. Adding one means a
new server release, and clients show a code they do not know as the code itself.

## Accounts and stats

| Setting | Default | Read by | Meaning |
|---|---|---|---|
| `DATABASE_URL` | empty | hub | PostgreSQL JDBC URL, such as `jdbc:postgresql://localhost:5432/openrac`. Empty: accounts and stats live in memory. See [database.md](database.md) |
| `DATABASE_USER` | `openrac` | hub | The database user |
| `DATABASE_PASSWORD` | empty | hub | The database password |
| `DATABASE_POOL_SIZE` | `8` | hub | Pooled database connections |
| `MIN_PASSWORD_LENGTH` | `4` | hub | The shortest password a first login registers |
| `PASSWORD_COST` | `10` | hub | bcrypt cost (rounds as a power of two) |
| `SESSION_DAYS` | `30` | hub | How long a login's session token lasts |
| `LOGIN_FAILURES` | `10` | hub | Wrong passwords one address may send per window before it is told `TRY_LATER` |
| `LOGIN_WINDOW_SECONDS` | `600` | hub | The window for `LOGIN_FAILURES` |
| `ACCOUNT_THREADS` | `4` | hub | Threads for password hashing and database work |

## Logging

| Setting | Default | Read by | Meaning |
|---|---|---|---|
| `DEBUG_LEVEL` | `all` | both | What shows: `all`, `verbose`, `warning`, `error` or `none` |
| `DEBUGGING` | `false` | both | `true` adds the debug lines (party chat, dropped datagrams), and with `DEBUG_LEVEL=verbose` also every game data packet and UDP state datagram |

Logging is coloured and levelled.

## Parties

| Setting | Default | Read by | Meaning |
|---|---|---|---|
| `MAX_PARTY_SIZE` | `4` | game server | Players per party, the host included |
| `FAMILY_PARTY_SIZES` | `rac3-pal-1:8,rac3-ntsc-1:8` | game server | Families whose games hold another number of players: `family:size`, comma separated, up to 24. Up Your Arsenal's 8 is what the unmodified game shows; larger rooms need a game that lists more |
| `MAX_DESCRIPTION_LENGTH` | `48` | game server | Characters in a party description |
| `MAX_LISTED_PARTIES` | `32` | game server | Parties one `LIST` answer carries |
| `MAX_ADVERT_LENGTH` | `512` | game server | Bytes of the advert a host may list its party with |

## Lobby screens

| Setting | Default | Read by | Meaning |
|---|---|---|---|
| `UI_DIRECTORY` | `ui` | game server | Where the [UI descriptions](lobby-screens.md) are read: `<family>.json` or `<game>.json` |

## Mod sets

| Setting | Default | Read by | Meaning |
|---|---|---|---|
| `MODSET_MAX_BODY_BYTES` | `16384` | game server | What a set may weigh compressed |
| `MODSET_MAX_JSON_BYTES` | `65536` | game server | What a set may weigh inflated |
| `MODSET_CAPACITY` | `50000` | game server | Sets kept; past this the one read longest ago goes |
| `MODSET_TTL_DAYS` | `30` | game server | Days a set lives after its last read |
| `MODSET_FILE` | `data/modsets.bin` | game server | Where sets are saved, so a restart keeps the codes |
| `MODSET_SAVE_MINUTES` | `5` | game server | How often the sets are saved |
| `MODSET_PUTS_PER_MINUTE` | `10` | game server | Stores per address |
| `MODSET_GETS_PER_MINUTE` | `60` | game server | Fetches per address |
| `HTTP_TIMEOUT_SECONDS` | `10` | game server | Seconds a request has to arrive whole |

## Limits

What these limits do is in [limits.md](limits.md).

| Setting | Default | Read by | Meaning |
|---|---|---|---|
| `CHAT_BURST`, `CHAT_PER_SECOND` | `5`, `1` | game server | Chat per connection: a burst, then the steady rate per second |
| `PARTY_BURST`, `PARTY_PER_SECOND` | `5`, `1` | game server | Party requests per connection |
| `DATA_BURST`, `DATA_PER_SECOND` | `120`, `60` | game server | `DATA` per connection, and UDP `STATE` per session |
| `RELAY_BURST`, `RELAY_PER_SECOND` | `240`, `120` | game server | `RELAY` per connection |
| `TIME_BURST`, `TIME_PER_SECOND` | `5`, `2` | game server | `TIME` per connection |
| `UI_BURST`, `UI_PER_SECOND` | `5`, `1` | game server | `UI` per connection |
| `STRIKE_BURST` | `20` | game server | Dropped packets a connection may run up before it is closed |
| `STRIKES_FORGIVEN_PER_SECOND` | `1` | game server | Strikes forgiven per second |
| `MAX_CONNECTIONS_PER_ADDRESS` | `8` | both | Open connections one address may hold |
| `HELLO_TIMEOUT_SECONDS` | `10` | both | Seconds a connection has to say hello (or register) |
| `UDP_TIMEOUT_SECONDS` | `15` | game server | Seconds without a valid datagram before a session's state goes back to TCP |
| `RESUME_SECONDS` | `120` | game server | Seconds a dropped player's slot waits for it |
| `PARTY_IDLE_SECONDS` | `5` | game server | Silence that counts as a drop, for clients that come back |
| `KEEP_ALIVE_IDLE_SECONDS` | `30` | game server | Silence after which a client that promised `TIME` (client bit 2) is dropped |
| `HOST_GRACE_SECONDS` | `15` | game server | Seconds a party waits for a dropped host before the next in line takes over |
