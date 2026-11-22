package com.openrac.server.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import com.openrac.net.NetworkConstants;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.out.UiPacket;
import com.openrac.server.Constants;
import com.openrac.server.Session;
import com.openrac.utils.logger.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves the screens a game's multiplayer menus show: one UI description per game family, read
 * from the configured directory or the built-in one of the family's game, which matches the game's
 * own menus. A description's "server" block stays here: its notices answer the actions players choose.
 */

public class UiController {
    private static final Map<String, Description> descriptions = new ConcurrentHashMap<>(); // By file stem.

    private UiController() {}

    /**
     * Reads every description in a directory, keyed by file stem: a family ("rac3-pal-1") or a game ("rac3").
     * A file that is not a valid description is logged and left out.
     * @param directory The directory to read; a missing one holds nothing.
     */

    public static void load(Path directory) {
        descriptions.clear();

        // Hosts that customise nothing need no directory.
        if (!Files.isDirectory(directory)) return;

        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) load(file, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            Log.error("Cannot read UI descriptions in " + directory + ": " + exception.getMessage() + ".");
        }
    }

    /**
     * Reads one description file and keeps it under its stem when it is valid.
     * @param file The file, for its stem and the log.
     * @param text Its contents.
     */

    private static void load(Path file, String text) {
        String stem = file.getFileName().toString().replaceFirst("\\.json$", "");
        Description description = parse(text, file.toString());

        // A family falls back past an invalid file.
        if (description == null) return;

        descriptions.put(stem, description);
        Log.info("UI description " + stem + " (revision " + description.revision() + ") loaded.");
    }

    /**
     * Sends a session its family's description, remembering the family for its actions.
     * @param session The asking session.
     * @param family The session's game family.
     */

    public static void handleGet(Session session, String family) {
        session.setUiFamily(family);

        Description description = find(family);

        // No description: the client keeps the game's own screens.
        if (description == null) {
            session.send(new UiPacket(0, new byte[0]));
            return;
        }

        session.send(new UiPacket(description.revision(), description.json()));
    }

    /**
     * Answers a server action a player chose with the description's notice for it, if any.
     * @param session The session that chose it.
     * @param key The action's key.
     * @param value The value chosen.
     */

    public static void handleAction(Session session, String key, int value) {
        // A session that never asked for screens has no description.
        Description description = session.getUiFamily() == null ? null : find(session.getUiFamily());

        Log.info("Session " + session.getId() + " (" + session.getName() + ") chose UI action " + key + " = " + value + ".");

        // Actions are only answered under a description that names them.
        if (description == null || !description.notices().containsKey(key)) return;

        session.send(new UiPacket(Opcodes.Ui.NOTICE, description.notices().get(key)));
    }

    /**
     * Parses and checks a description: its client part a schema 1 object within the wire limit,
     * its server part a map of notices.
     * @param text The file's JSON.
     * @param source The file's name, for the log.
     * @return The description, or null when it is invalid.
     */

    static Description parse(String text, String source) {
        try {
            JsonElement root = JsonParser.parseString(text);

            // A description is one JSON object.
            if (!root.isJsonObject()) throw new JsonParseException("not an object");

            JsonObject client = root.getAsJsonObject().deepCopy();
            JsonElement server = client.remove("server");
            JsonElement schema = client.get("schema");

            // Clients read schema 1 alone and refuse the rest.
            if (schema == null || !schema.isJsonPrimitive() || schema.getAsInt() != NetworkConstants.UI_SCHEMA) throw new JsonParseException("schema is not " + NetworkConstants.UI_SCHEMA);

            byte[] json = client.toString().getBytes(StandardCharsets.UTF_8);

            // Clients refuse larger descriptions whole.
            if (json.length > NetworkConstants.MAX_UI_LENGTH) throw new JsonParseException(json.length + " bytes, over " + NetworkConstants.MAX_UI_LENGTH);

            // A file without a revision counts as the first.
            int revision = client.has("revision") ? client.get("revision").getAsInt() : 0;

            return new Description(revision, json, readNotices(server));
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException | NumberFormatException exception) {
            Log.error("UI description " + source + " is invalid, skipping: " + exception.getMessage() + ".");
            return null;
        }
    }

    /**
     * Reads the notices of a description's server block.
     * @param server The block, or null.
     * @return The notices by action key.
     */

    private static Map<String, String> readNotices(JsonElement server) {
        Map<String, String> notices = new HashMap<>();

        // A description without a server block answers no action.
        if (server == null) return notices;

        JsonObject block = server.getAsJsonObject();
        // A server block may hold no notices yet.
        JsonObject read = block.has("notices") ? block.getAsJsonObject("notices") : new JsonObject();

        for (Map.Entry<String, JsonElement> entry : read.entrySet()) {
            String notice = entry.getValue().getAsString();

            // Games draw a notice on one line.
            if (notice.length() > Constants.MAX_CHAT_LENGTH) throw new JsonParseException("notice " + entry.getKey() + " is too long");

            notices.put(entry.getKey(), notice);
        }

        return notices;
    }

    /**
     * Finds a family's description: its own file, its game's file, then its game's built-in one.
     * @param family The game family ("rac3-pal-1").
     * @return The description, or null when there is none.
     */

    private static Description find(String family) {
        // A family names its game first: "rac3-pal-1" is a rac3 disc.
        String game = family.contains("-") ? family.substring(0, family.indexOf('-')) : family;

        // A host's file for one family wins over its game's.
        if (descriptions.containsKey(family)) return descriptions.get(family);

        // A file for every disc of the game.
        if (descriptions.containsKey(game)) return descriptions.get(game);

        return builtIn(game);
    }

    /**
     * Reads a game's built-in description, which describes its menus as they are.
     * @param game The game ("rac3").
     * @return The description, or null when the server has none for that game.
     */

    private static Description builtIn(String game) {
        try (InputStream stream = UiController.class.getResourceAsStream("/ui/" + game + ".json")) {
            // Only some games have their menus described.
            if (stream == null) return null;

            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8), "built-in " + game);
        } catch (IOException exception) {
            Log.error("Cannot read the built-in UI description of " + game + ": " + exception.getMessage() + ".");
            return null;
        }
    }

    /**
     * A loaded description: its revision, the JSON clients get, and the notices answering its actions.
     * @param revision The host's number for this description.
     * @param json The client part, UTF-8 JSON.
     * @param notices The lines sent back per action key.
     */

    record Description(int revision, byte[] json, Map<String, String> notices) {}
}
