# Self-hosting

The server is open source, and anyone can run any part of it. Every setting named here is in
[configuration.md](configuration.md).

## Setups

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

## Ports

| Port | Protocol | Service | Setting |
|---|---|---|---|
| 7177 | TCP | the hub | `HUB_PORT` |
| 7770 | TCP and UDP | a game server: lobbies, game data, UDP game state | `PORT` |
| 7771 | TCP | a game server's mod set store (HTTP) | `HTTP_PORT` |

Both TCP and UDP must be open for a game server: game state uses UDP where it gets through, and
falls back to TCP where it does not (see [game-server.md](game-server.md#udp)).

## Jars

Building needs Gradle's toolchain, as in [development.md](development.md#build-and-run). Build the
one-jar services and run them with the scripts, from the repository root:

```sh
./gradlew :hub:buildHubJar               # services/hub/build/libs/openrac-hub-1.0.0.jar
./gradlew :server:buildServerJar         # services/server/build/libs/openrac-server-1.0.0.jar
./scripts/run_hub.sh                     # or ./scripts/run_hub.sh --hub-port 9177
./scripts/run_server.sh                  # or ./scripts/run_server.sh --port 9000
```

The scripts run the jars with ZGC and compact object headers. How a service shuts down is in
[architecture.md](architecture.md#modes).

## Docker

The image holds both services, built with the JDK and run on the JRE alone as a non-root user. Only
Docker is needed: no Java or Gradle on the host.

### Docker Compose

`docker-compose.yml` runs a whole network on one machine: PostgreSQL, the hub and one game server
registered with it.

```sh
docker compose up -d                     # build and start: 7177 (hub), 7770 TCP and UDP, 7771 (game server)
docker compose logs -f                   # follow the logs
docker compose down                      # stop it (the volumes keep the accounts and mod sets)
```

Put a `.env` beside `docker-compose.yml` before the first start, with at least
`DATABASE_PASSWORD` (the PostgreSQL password, `openrac` otherwise) and, for players outside this
machine's network, `REGION_HOST` (the address they reach the game server on) and `REGION_NAME`.
It also overrides `.env.defaults` inside both containers.

### One container per service

Without Compose, the default command runs `server.jar`:

```sh
docker build -t openrac-server .
docker run -d --name openrac-server -p 7770:7770/tcp -p 7770:7770/udp -p 7771:7771/tcp \
    -v openrac-modsets:/app/data openrac-server
docker run -d --name openrac-hub -p 7177:7177/tcp openrac-server hub.jar
```

The `/app/data` volume keeps the mod sets across rebuilds. To listen on another port, set `PORT` and
publish the same number on both sides (`-e PORT=9000 -p 9000:9000/tcp -p 9000:9000/udp`): a game
server tells the hub its port, so the two must match.
