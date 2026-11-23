# Hub

The hub (`services/hub`) is what a game reaches first: what a game's `OPENRAC_SERVER` names,
`hub.openrac.dev:7177` by default. It lists the game servers that registered with it, keeps the
[accounts](accounts.md) and [stats](stats.md), and is the only part that talks to the
[database](database.md). Lobbies, parties and game data live on the game servers; the hub closes a
connection that sends them. The packets are in [protocol.md](protocol.md#hub-opcodes).

## Registration

A game server connects to `HUB_ADDRESS` and sends `REGISTER` as its first packet, instead of
`HELLO`: the hub's key, its region code (`REGION`), where players reach it, its game port, its player
count and its own name (`SERVER_NAME`, which may be empty). The hub answers `HUB REGISTERED` with the server's id, or refuses with an
`ERROR` and closes:

- `REGION_KEY` when the hub has a `REGION_KEY` set and the server sent another one. The server stops
  trying until its operator fixes the key.
- `HUB_FULL` when the hub already lists 32 servers. The server tries again later.

A registration comes once per connection. The region must be a known
[region code](configuration.md#region-codes) (in any case; the hub lists it in upper case), the
server name 0 to 32 printable ASCII characters and the port 1 to 65,535; anything else closes the
link. A server from before region codes, still sending a free-text region such as `Europe`, is
closed too; `Local` reads as `LOCAL`. A server from before names sends none and is listed with an
empty one.

## Heartbeat

Every `REGION_REPORT_SECONDS` (5) a registered server sends `HUB STATUS` with its player and party
counts. A server the hub has not heard from for `SERVER_TIMEOUT_SECONDS` (20) is dropped, as is one
whose link closes or that says `HUB CLOSING`. A server whose link drops connects again, one attempt
at a time, and registers anew under a new id.

## The server list

A client asks for the list in one of two ways, after `HELLO`:

- `REGIONS`: a snapshot of every listed server: its region code, address, port and players, then
  every server's name after the list.
- `SERVERS WATCH`: `SERVERS ADDED` for every listed server, then each change as it happens
  (`ADDED`, `CHANGED` when a count changed, `REMOVED`).

Clients show a server as its name and region, such as "Thyrannosis USA (United States)", or the
region alone when the name is empty. The names of the regions are the clients' own: the hub sends
only codes.

Both requests carry the game family string, but the hub lists every server whatever the family for
now. A game server that sends either is closed.

The hub answers a client's `HELLO` with its id and a `UDP` offer whose feature bits say it answers
`REGIONS` and `SERVERS` (see [feature bits](protocol.md#feature-bits)). It also answers `TIME`.

## Addresses

Where players reach a game server: its `REGION_HOST` when set. Empty, the hub lists the address the
server connected from, except when that is the hub's own machine or a private network (loopback,
LAN, link-local, IPv6 ULA): players then get the hub's address, the one they already reached. A
server on another LAN machine than its hub sets `REGION_HOST`.

## Limits

The hub's per-address connection limit, hello deadline and request rates are in
[limits.md](limits.md#the-hub).
