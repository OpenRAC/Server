# Mod sets

A small HTTP side-port on each game server (`HTTP_PORT`, 7771, TCP) stores the launcher's mod sets
behind short codes: what friends type to get the same mods at the same versions. It has nothing to
do with game traffic and holds only public metadata. No login: a set names catalogue mods and their
hashes, and the launcher checks everything again and shows it to the player before anything
changes.

## Routes

| Route | Body | Answer |
|---|---|---|
| `PUT /v1/modsets` | the set, zlib-compressed JSON | `200` `{"code":"K7M29QXR"}`; `400` bad set; `413` too large; `429` over the rate |
| `GET /v1/modsets/<CODE>` | none | `200` the compressed set; `404` unknown or expired; `429` over the rate |

## Rules

- **Codes.** 8 random characters of the party alphabet (40 bits), any case, with or without a dash.
  The same set gets the same code: the hash leaves out only its `created` date.
- **Life.** 30 days from the last read (`MODSET_TTL_DAYS`), so a party's code stays alive. Past 50,000
  sets (`MODSET_CAPACITY`) the set read longest ago goes.
- **Checks.** At most 16 KB compressed (`MODSET_MAX_BODY_BYTES`), 64 KB inflated
  (`MODSET_MAX_JSON_BYTES`) and 40 mods in each list. The JSON is parsed strictly and may hold only
  the format's keys. `source.url` must be a `https://github.com/` repository, and no other value may
  hold an address, so the store cannot be used to host anything else.
- **Rates.** Per address: 10 stores and 60 fetches a minute (`MODSET_PUTS_PER_MINUTE`,
  `MODSET_GETS_PER_MINUTE`), with the game server's connection limit (see
  [limits.md](limits.md#the-game-server)). A connection serves one request and has 10 seconds
  (`HTTP_TIMEOUT_SECONDS`). Behind a proxy every player shares its address.
- **Saved.** To `MODSET_FILE` every `MODSET_SAVE_MINUTES` and at shutdown, so a restart keeps the codes.
  A file that cannot be read is logged and the store starts empty; its codes then answer `404`.
