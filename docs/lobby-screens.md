# Lobby screens

A game whose multiplayer menus are drawn natively (Up Your Arsenal) asks the game server which
screens to show: their lists' columns, the rows shown at once, extra screens, options, a message of
the day and announcements, and the largest room. The answer is a UI description, JSON of schema 1
(`UI_SCHEMA`), at most 32,768 bytes (`MAX_UI_LENGTH`); the game checks it whole and keeps its own
menus when it is missing or refused. The packets are in [protocol.md](protocol.md#ui-opcodes).

## The schema

The schema is OpenRAC's
[docs/port/MULTIPLAYER_UI.md](https://github.com/OpenRAC/OpenRAC/blob/develop/docs/port/MULTIPLAYER_UI.md).
The server checks only what it needs:

- The file is one JSON object with `"schema": 1`. Anything else is refused.
- `revision` is an int sent with the description (0 when the file has none).
- After the `server` block is taken out, the rest must fit in 32,768 bytes.

The built-in description of Up Your Arsenal
(`services/server/src/main/resources/ui/rac3.json`) is the smallest example:

```json
{
  "schema": 1,
  "name": "Up Your Arsenal",
  "revision": 1,
  "screens": [
    {
      "id": "game_list",
      "columns": [ { "field": "name", "width": 100 } ],
      "items": [ { "kind": "table", "key": "games", "action": "game:join" } ]
    }
  ]
}
```

## Customising the screens

Hosts customise the screens with files in `UI_DIRECTORY` (`ui`). For a `GET` with a family, the
server answers with the first that exists:

1. `<family>.json` for one disc (`rac3-pal-1.json`);
2. `<game>.json` for every disc of a game (`rac3.json`), the family's part before its first dash;
3. the server's built-in description of that game, which describes its menus as they are
   (`services/server/src/main/resources/ui`);
4. none: an empty `DESCRIPTION`, and the game keeps its own screens.

Files are read at start; an invalid one is logged and skipped.

## Server actions

A file may hold a `server` block that never reaches clients:

```json
{"server": {"notices": {"<key>": "line"}}}
```

When the player chooses a `server:<key>` action, the game sends `UI ACTION` with the key and a
value. The server answers with `NOTICE` and the line the block names for that key, or with nothing
when the description names none. A notice is at most 256 characters. Requests share the
`UI_BURST` / `UI_PER_SECOND` rate.

## Reference

What a description can offer (custom modes on a stock mode, custom maps loading a stock level,
per-game rules the host sets) follows the Horizon Private Server project
(https://github.com/Horizon-Private-Server/horizon-server), read for reference only.
