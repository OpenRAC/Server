# Protocol

The wire format shared by clients, the hub and game servers. The definitions live in
`services/common`: `Packets` (packet ids), `Opcodes` (opcodes and error reasons), `Datagrams`
(UDP kinds) and `NetworkConstants` (sizes, feature bits and client bits). What each request does is
described in the topic files linked from each section.

## Append-only

A packet id is the ordinal of its `Packets` constant, and an opcode or error reason the ordinal of
its `Opcodes` enum constant. Values are only ever appended, never reordered or removed, so shipped
clients keep working. Both ends close the connection on a packet or opcode they do not know, so a
server announces the requests it takes with [feature bits](#feature-bits), and a client announces
what it reads with [client bits](#hello-client-bits).

## Framing

Every packet is a frame: a 4-byte big-endian length, then the packet id byte, then an opcode byte
for packets that have one, then the payload. Frames are at most 65,536 bytes, length included.
Integers are big-endian; a string is a 2-byte length then UTF-8 bytes (-1 for empty).

The first packet must be `HELLO` (or `REGISTER`, from a game server to the hub). Anything malformed,
unknown or oversized closes the connection. Names and versions are 1 to 32 characters, chat
messages 1 to 256.

## Packets

| Id | Packet | Direction | Payload |
|---|---|---|---|
| 0 | `HELLO` | client → server or hub | version string, display name string, then the [client bits](#hello-client-bits) byte, optional |
| | | server or hub → client | session id int |
| 1 | `PARTY` | both | opcode byte, then as [below](#party-opcodes) |
| 2 | `CHAT` | client → server | message string |
| | | server → members | sender id int, message string |
| 3 | `DATA` | client → server | target id int, opaque bytes to the end of the frame |
| | | server → client | sender id int, opaque bytes |
| 4 | `ERROR` | server or hub → client, hub → game server | reason byte ([reasons](#error-reasons)) |
| 5 | `TIME` | client → server or hub | client time long (ms) |
| | | server or hub → client | the client time echoed, server time long (ms since the server started) |
| 6 | `UDP` | server or hub → client | UDP token long, UDP port int, [feature bits](#feature-bits) byte, resume token long; sent right after the `HELLO` answer |
| 7 | `RELAY` | client → server | target id int (0 every other member), opaque bytes to the end of the frame |
| | | server → client | sender id int, opaque bytes |
| 8 | `REGIONS` | client → hub | the game family string |
| | | hub → client | count byte, then per game server: region code string, host string (empty: the address the client reached the hub on), port int, players int; then, appended, each server's name string in the same order (empty: none). An older hub stops after the list |
| 9 | `REGISTER` | game server → hub | key string, region code string ([configuration.md](configuration.md#region-codes)), host string (empty: see [hub.md](hub.md#addresses)), port int, players int, server name string (0 to 32 printable ASCII characters, empty: none); the first packet on the link, instead of `HELLO` |
| 10 | `UI` | both | opcode byte, then as [below](#ui-opcodes) |
| 11 | `HUB` | both | opcode byte, then as [below](#hub-opcodes): a game server's link to the hub |
| 12 | `SERVERS` | both | opcode byte, then as [below](#servers-opcodes): the live game server list |
| 13 | `ACCOUNT` | both | opcode byte, then as [below](#account-opcodes): logins at the hub, and proving them to a game server |
| 14 | `STATS` | both | opcode byte, then as [below](#stats-opcodes): totals and the game's own ladders, at the hub |

`DATA`, `RELAY`, `TIME` and `CHAT` routing is in [game-server.md](game-server.md#routing).

## PARTY opcodes

Behaviour: [game-server.md](game-server.md#parties).

| Opcode | Name | Direction | Payload |
|---|---|---|---|
| 0 | `CREATE` | client → server | description string, optional (an empty body means none), then the game family string, optional; answered with `INFO` |
| 1 | `JOIN` | client → server | join code string (6 characters, any case), then the game family string, optional |
| 2 | `LEAVE` | client → server | none |
| 3 | `INFO` | server → client | code string, host id int, member count byte, then id int + name string per member, then description string; once either means something, the host's epoch int, then the count byte and ids int of the members away |
| 4 | `JOINED` | server → members | id int, name string |
| 5 | `LEFT` | server → members | id int |
| 6 | `END` | server → members | none; the host left, so the party is gone |
| 7 | `FIND` | client → server | the game family string, optional; answered with `INFO` |
| 8 | `LIST` | client → server | the game family string, optional, then a flags byte, optional (bit 0: with adverts); allowed in or out of a party |
| | | server → client | count byte (at most `MAX_LISTED_PARTIES`, 32), then per party: code string, host name string, member count byte, max party size byte, description string; with adverts, the advert's length short and its bytes |
| 9 | `HOST_CHANGED` | client → server | decline byte, count byte, then id int per member; from the host only (else `NOT_HOST`) |
| | | server → members | new host id int, epoch int (one more at each change) |
| 10 | `RESUME` | client → server | party code string, session id int, resume token long; asked after `HELLO` on a new connection |
| | | server → members | id int, byte: 0 its connection dropped and its slot waits, 1 it came back |
| 11 | `KICK` | client → server | member id int; from the host only (else `NOT_HOST`) |
| 12 | `KICKED` | server → member | none; the host removed it from the party |
| 13 | `ADVERT` | client → server | opaque bytes to the end of the frame, at most `MAX_ADVERT_LENGTH` (512); from the host only (else `NOT_HOST`, or `NOT_IN_PARTY` outside a party) |
| 14 | `SLOTS` | server → members that read it | count byte, then id int + slot byte per member; only to clients that set [client bit](#hello-client-bits) 1 (2) |

## UI opcodes

Behaviour: [lobby-screens.md](lobby-screens.md).

| Opcode | Name | Direction | Payload |
|---|---|---|---|
| 0 | `GET` | client → server | the game family string |
| 1 | `DESCRIPTION` | server → client | revision int, then the JSON's UTF-8 bytes to the end of the frame; none (empty) keeps the game's own screens |
| 2 | `ACTION` | client → server | action key string (32 characters at most), value int: a `server:<key>` action the player chose |
| 3 | `NOTICE` | server → client | a line the game shows, answering an action |

## HUB opcodes

Between a game server and the hub. Behaviour: [hub.md](hub.md).

| Opcode | Name | Direction | Payload |
|---|---|---|---|
| 0 | `REGISTERED` | hub → game server | the server's id in the list, int; the answer to `REGISTER` |
| 1 | `STATUS` | game server → hub | players int, parties int; the heartbeat, every `REGION_REPORT_SECONDS` (5) |
| 2 | `CLOSING` | game server → hub | none; the server is shutting down and leaves the list at once |
| 3 | `VERIFY` | game server → hub | request int, session token string; whose login a player's token is |
| 4 | `VERIFIED` | hub → game server | request int, account id long (0: no such session), account name string |
| 5 | `RESULT` | game server → hub | a finished game: family string, game name string, mode int, map int, duration int (seconds), winning team int (-1 none), count byte (at most 32), then per player: account id long (0 a guest), name string, team int (-1 none), kills int, deaths int, score int, won byte; no answer |

## SERVERS opcodes

Between a client and the hub. Behaviour: [hub.md](hub.md#the-server-list).

| Opcode | Name | Direction | Payload |
|---|---|---|---|
| 0 | `WATCH` | client → hub | the game family string; answered with `ADDED` for every listed server, then each change as it happens |
| 1 | `ADDED` | hub → client | id int, region code string, server name string (empty: none), host string (empty: the hub's address), port int, players int, parties int |
| 2 | `CHANGED` | hub → client | id int, players int, parties int; only when a count changed |
| 3 | `REMOVED` | hub → client | id int; the server closed, said `CLOSING` or went quiet |

## ACCOUNT opcodes

Behaviour: [accounts.md](accounts.md).

| Opcode | Name | Direction | Payload |
|---|---|---|---|
| 0 | `LOGIN` | client → hub | name string, password string; a name nobody has registers with this password |
| 1 | `LOGGED_IN` | hub → client | account id long, name string (as registered), session token string, created byte (1: this login registered it) |
| | | game server → client | account id long, name string, empty token, 0; the answer to `TOKEN` |
| 2 | `TOKEN` | client → game server | the session token from the hub's `LOGGED_IN` |
| 3 | `LOGOUT` | client → hub | the session token; it stops working |

## STATS opcodes

Between a client and the hub. Every request carries the game family string right after the opcode,
then the fields below. Answers carry only the fields below, with no family ahead of them. Each
`LADDER` and `CAREER` opcode answers one of the game's own Medius requests, so a lobby can map them
one to one. Behaviour: [stats.md](stats.md).

| Opcode | Name | Medius request | Direction | Payload |
|---|---|---|---|---|
| 0 | `TOTALS` | | client → hub | account id long |
| | | | hub → client | account id long, family string, games int, wins int, kills int, deaths int, score long (all 0 before a game) |
| 1 | `LADDER_WRITE` | `LADDER_STATS_WRITE` 4/0x0B | client → hub | ladder type int, count byte (at most 100), then the values int; the logged-in account's own row, replaced |
| | | | hub → client | none (else `NOT_LOGGED_IN` or `TRY_LATER`) |
| 2 | `LADDER_READ` | `LADDER_STATS_READ` 4/0x0D | client → hub | account id long, ladder type int |
| | | | hub → client | account id long, ladder type int, count byte, then the values int (count 0: none written) |
| 3 | `LADDER_PAGE` | `LADDER_PAGE` 4/0x0F | client → hub | ladder type int, sort index byte (0-99), descending byte, start position int (from 1), count byte (at most 32) |
| | | | hub → client | total int (accounts on the ladder), count byte, then per row: position int, account id long, name string, count byte and the values int |
| 4 | `LADDER_POSITION` | `LADDER_POSITION` 1/0xF1 | client → hub | account id long, ladder type int, sort index byte, descending byte |
| | | | hub → client | account id long, position int (0: not on the ladder), total int |
| 5 | `CAREER_WRITE` | `CAREER_STATS_UPLOAD` 1/0x11 | client → hub | bytes to the end of the frame (at most 256); the logged-in account's own, replaced |
| | | | hub → client | none (else `NOT_LOGGED_IN` or `TRY_LATER`) |
| 6 | `CAREER_READ` | the Stats of account info and player lists | client → hub | account id long |
| | | | hub → client | account id long, the bytes to the end of the frame (none: never written) |

A `LADDER_WRITE` over 100 values, a `CAREER_WRITE` over 256 bytes or a sort index of 100 or more
closes the connection.

## ERROR reasons

| Reason | Name | Meaning |
|---|---|---|
| 0 | `PARTY_NOT_FOUND` | no party has that code, or it belongs to another family and the client named none |
| 1 | `PARTY_FULL` | the party has no room |
| 2 | `ALREADY_IN_PARTY` | a session belongs to one party at a time |
| 3 | `NOT_IN_PARTY` | the request needs a party |
| 4 | `NOT_HOST` | only the host may send it |
| 5 | `RESUME_REFUSED` | its slot is gone, or the token is wrong |
| 6 | `OTHER_GAME` | the party plays another game family; the party's family string follows |
| 7 | `REGION_KEY` | the hub asks for another key; the server stops trying |
| 8 | `HUB_FULL` | the hub lists 32 servers; the server tries again |
| 9 | `WRONG_PASSWORD` | the account exists and the password is not its own |
| 10 | `INVALID_ACCOUNT` | a name or password the hub does not take |
| 11 | `SESSION_INVALID` | an unknown, expired or ended token |
| 12 | `TRY_LATER` | too many wrong passwords from the address, the hub unreachable, its database down, or a request over the hub's rate |
| 13 | `NOT_LOGGED_IN` | a stats write on a hub connection with no account login |

## Feature bits

`UDP`'s feature bits say which requests this server takes, since an unknown one closes the
connection. A client from before them stops reading at the port.

| Bit | Value | Constant | The sender takes |
|---|---|---|---|
| 0 | 1 | `FEATURE_KICK` | `PARTY KICK` |
| 1 | 2 | `FEATURE_RESUME` | `PARTY RESUME` |
| 2 | 4 | `FEATURE_ADVERT` | `PARTY ADVERT` |
| 3 | 8 | `FEATURE_RELAY` | `RELAY` |
| 4 | 16 | `FEATURE_REGIONS` | `REGIONS` |
| 5 | 32 | `FEATURE_UI` | `UI` |
| 6 | 64 | `FEATURE_SERVERS` | `SERVERS` |

A game server offers bits 0 to 3 and 5 (47). The hub sends a `UDP` offer too, with token and port
0 (it takes no datagrams) and bits 4 and 6 (80); a game server sends neither, since the list is the
hub's.

## HELLO client bits

The optional byte after the name in `HELLO`. What each promise changes is in
[game-server.md](game-server.md#resuming).

| Bit | Value | Constant | The client |
|---|---|---|---|
| 0 | 1 | `CLIENT_RESUMES` | comes back with `RESUME` after a drop |
| 1 | 2 | `CLIENT_SLOTS` | reads `SLOTS` |
| 2 | 4 | `CLIENT_KEEPS_ALIVE` | sends `TIME` every few seconds |

## Datagrams

UDP runs on the game server's TCP port. Datagrams have no length prefix and are at most 1,200 bytes.
Integers are big-endian. Behaviour: [game-server.md](game-server.md#udp).

| Direction | Layout |
|---|---|
| client → server | session id int, token long, kind byte, then the kind's payload |
| server → client | kind byte, then the kind's payload |

| Kind | Name | Payload |
|---|---|---|
| 0 | `BIND` | client → server: none |
| 1 | `STATE` | client → server: target id int, opaque bytes to the end; server → client: sender id int, opaque bytes |
