# Game server

A game server (`services/server`) runs the lobbies and game rooms. It links players into parties and
forwards their traffic; the host's game is authoritative, and the server never reads the game data
it forwards. It registers with a hub (see [hub.md](hub.md)) and also serves
[lobby screens](lobby-screens.md) and [mod sets](mod-sets.md). The packets are in
[protocol.md](protocol.md).

## Sessions

A client says `HELLO` with its version, its display name and optionally its
[client bits](protocol.md#hello-client-bits). The server answers `HELLO` with its session id, then
`UDP` with the UDP token, the port, the [feature bits](protocol.md#feature-bits) and a resume token.
Session ids start at 1. A player can then prove a hub login with `ACCOUNT TOKEN` (see
[accounts.md](accounts.md#proving-a-login-to-a-game-server)).

## Parties

A session belongs to one party at a time. Parties hold `MAX_PARTY_SIZE` (4) players, the host
included, or what `FAMILY_PARTY_SIZES` gives their family, up to 24 (Up Your Arsenal's: 8, what the
unmodified game shows).

- `CREATE` makes the creator the host of a new party, answered with `INFO`. Match finding can fill
  it, and its code invites.
- `JOIN` enters a party by its 6-character code, in any case.
- `FIND` joins the oldest party of the asker's family with room, else hosts a new one with no
  description; answered with `INFO`.
- `LIST` names the parties of the requester's game family with room (members below the party size),
  oldest first, without the requester's own party and without parties waiting for a dropped host.
  Created and match-found parties both appear. One answer carries at most 32 parties
  (`MAX_LISTED_PARTIES`). `LIST` is allowed in or out of a party.
- `LEAVE` leaves. When the host leaves on purpose, the next in its `HOST_CHANGED` list follows at
  once (see [host handover](#host-handover)).
- `KICK`, from the host only: the member gets `KICKED`, the others `LEFT`. Its connection stays and
  its code still works, but `FIND` no longer puts it in that party. An id that is not a member's
  does nothing.

A party description is sanitised on the server: only printable ASCII (0x20 to 0x7E) is kept, the
ends are trimmed and it is cut to `MAX_DESCRIPTION_LENGTH` (48) characters.

### Adverts

An advert is what a host lists its party with, beside the description: game settings a lobby
shows, opaque to the server, at most `MAX_ADVERT_LENGTH` (512) bytes. A party starts with none
(length 0). The host replaces it with `ADVERT`, and `LIST` returns it to clients that ask for
adverts (flag bit 0).

### Slots

A client that sets client bit 1 gets `SLOTS` after `INFO` and whenever a member joins or leaves. A
member keeps its slot while it is in the party; a newcomer takes the lowest free one, and the host
starts at 0.

## Game families

Each party belongs to its host's game family: the game's `openrac_api.world_family`, such as
`rac1-60-1` (Ratchet & Clank 1, PAL and NTSC alike) or `rac2-pal-1` (each Going Commando disc is its
own for now). `CREATE`, `FIND`, `LIST` and `JOIN` end with the asker's family, after their other
fields, so shipped clients, which stop before it, still work: a request without one counts as
`rac1-60-1` (`DEFAULT_FAMILY`), since every client from before families is a Ratchet & Clank 1 game.
A family is up to 32 characters of printable ASCII without spaces; anything else closes the
connection.

`LIST` and `FIND` see only the asker's family. A `JOIN` by code into another family's party is
refused: with `OTHER_GAME` and the party's family when the client named its own, so the game can
say which game it is for, and with `PARTY_NOT_FOUND` when it did not, as an older client knows no
other reason.

## Routing

- **`DATA`**: from a member, the server sends it to the host and ignores the target. From the host,
  target 0 sends it to every other member, and a session id sends it to that member only.
- **`RELAY`**: any member may send to any other. Target 0 sends it to every other member, a session
  id to that member only. It is for games whose players all talk to each other (Up Your Arsenal's
  game rooms), has its own rate and never goes over UDP.
- **`CHAT`**: goes to the members with the sender's id.
- **`TIME`** lets each game stamp its data against one clock: from its send time, the echo and the
  server's time, a client estimates the round trip and its offset to the server's clock.

## Resuming

A client that can come back says so with client bit 0 in `HELLO`. Its connection dropping is then
no leaving: the others get `RESUME` with 0, and the session waits for `RESUME_SECONDS` (120),
keeping its id, its slot and its place in the party. On a new connection the client says `HELLO`,
then `RESUME`: refused with `RESUME_REFUSED`, or answered as if greeted again (`HELLO` with the old
id, `UDP`, `INFO`), the others told with `RESUME` 1. UDP is bound again from nothing.

In a party, a client that can come back and has been silent for `PARTY_IDLE_SECONDS` (5) on both
TCP and UDP is closed, since a machine that dies closes nothing. A client without that bit leaves
when its connection goes, as ever.

A client that sets client bit 2 promises `TIME` every few seconds: silent for
`KEEP_ALIVE_IDLE_SECONDS` (30, long enough for a map load), it is closed and leaves its party as on
a disconnect (a host's party then goes on as a host's leaving decides).

## Host handover

The host sends `HOST_CHANGED` with the members that may follow it, the first choice first,
replacing its last list (decline byte 0). A new host that cannot take over says so with decline
byte 1, and the next one is asked.

A host's connection dropping makes the party wait for `HOST_GRACE_SECONDS` (15): it can come back
as above, nobody can join or find the party meanwhile, and nothing is forwarded to it. Then the first
of the host's last `HOST_CHANGED` list who is there hosts, everyone is told with `HOST_CHANGED` (the
epoch grows by one at each change), and the old host stays as a member whose slot waits out the
rest of its time: if it comes back, `INFO` tells it the host and epoch of the party it finds. A host
that leaves on purpose is followed at once. A party whose host named nobody, or with nobody left to
follow, ends with `END`.

## UDP

UDP is optional. Connecting, parties, chat, the clock and errors only ever use TCP, and so do game
events that must arrive (`DATA`). Game state, which the next update replaces anyway, may use UDP.
A client whose UDP is blocked never binds and keeps sending and receiving state as TCP `DATA`. The
datagram layout is in [protocol.md](protocol.md#datagrams).

- **Token.** After answering `HELLO`, the server sends `UDP`: a random 64-bit token for the session
  and the port it takes datagrams on (the TCP port).
- **Binding.** Any valid datagram (the right session id and token) binds its sender's address to the
  session, and a later one from a new address rebinds it, as NAT may move the port. A client sends
  `BIND` every few seconds to keep its NAT mapping open; the server runs no timers for it.
- **Routing.** `STATE` follows the `DATA` rules. Each recipient gets it by UDP once bound, otherwise
  as a TCP `DATA` packet from the sender. A session the server has heard no valid datagram from for
  `UDP_TIMEOUT_SECONDS` (15) gets state over TCP again until its next datagram.
- **Drops.** A datagram over 1,200 bytes, cut short, of an unknown kind, or naming an unknown session
  or the wrong token is dropped without an answer.

## Limits

Rates, strikes and connection limits are in [limits.md](limits.md#the-game-server).
