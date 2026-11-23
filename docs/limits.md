# Limits

Every limit below is a setting in `.env.defaults`; see [configuration.md](configuration.md#limits).
The mod set store has its own, in [mod-sets.md](mod-sets.md#rules). Account login limits are in
[accounts.md](accounts.md#limits).

## The game server

Each connection has a rate per packet kind: a short burst, then a steady rate.

| Kind | Burst | Per second | Settings |
|---|---|---|---|
| Chat | 5 | 1 | `CHAT_BURST`, `CHAT_PER_SECOND` |
| Party requests (including `LIST` and `ADVERT`) | 5 | 1 | `PARTY_BURST`, `PARTY_PER_SECOND` |
| Game data (`DATA`) | 120 | 60 | `DATA_BURST`, `DATA_PER_SECOND` |
| Relayed data (`RELAY`) | 240 | 120 | `RELAY_BURST`, `RELAY_PER_SECOND` |
| Clock requests (`TIME`) | 5 | 2 | `TIME_BURST`, `TIME_PER_SECOND` |
| Lobby screen requests (`UI`) | 5 | 1 | `UI_BURST`, `UI_PER_SECOND` |

A packet over its rate is dropped and counts as a strike. A connection may run up `STRIKE_BURST`
(20) strikes, forgiven at `STRIKES_FORGIVEN_PER_SECOND` (1); one that keeps flooding is closed.

UDP `STATE` has the same rate per session as `DATA`. Over it, a datagram is dropped, never struck:
UDP never closes a session.

One address may hold `MAX_CONNECTIONS_PER_ADDRESS` (8) connections, and a connection that has not
said hello within `HELLO_TIMEOUT_SECONDS` (10) is closed.

## The hub

The hub keeps the same per-address connection limit and hello deadline. A client gets 10 requests
in a burst, then 2 a second (`HUB_REQUEST_BURST`, `HUB_REQUEST_PER_SECOND`); once logged in, 40 in a
burst, then 10 a second (`HUB_ACCOUNT_REQUEST_BURST`, `HUB_ACCOUNT_REQUEST_PER_SECOND`), as the game
asks for its ladders, career and account info right after login. A request past them is answered
with `ERROR TRY_LATER`, never dropped silently. Game servers are not rate limited, but a
registration must come first and only once.
