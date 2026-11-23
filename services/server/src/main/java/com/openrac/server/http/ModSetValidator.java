package com.openrac.server.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import com.openrac.server.Constants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Checks a mod set before it is stored: zlib-compressed JSON of the launcher's format 1, with only
 * that format's keys. The store therefore cannot be used to host anything else. The launcher's own
 * parser (launcher/core/src/modset.rs) applies the same rules and stays the authority.
 */

public final class ModSetValidator {
    private static final Set<String> SET_KEYS = Set.of("format", "name", "created", "game", "discs", "openrac", "mods", "extras", "manual", "packs", "digest");
    private static final Set<String> MOD_KEYS = Set.of("id", "version", "kind", "source", "sums", "settings");
    private static final Set<String> SOURCE_KEYS = Set.of("url", "path", "tag", "commit");
    private static final Set<String> MANUAL_KEYS = Set.of("id", "version", "name");
    private static final Set<String> PACK_KEYS = Set.of("id", "digest");
    private static final Set<String> KINDS = Set.of("gameplay", "input", "cosmetic", "solo");

    private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern KEY = Pattern.compile("[a-z0-9_]+");
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(-[A-Za-z0-9.-]+)?");
    private static final Pattern URL = Pattern.compile("https://github\\.com/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9._/-]+");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]+");

    private static final int MAX_ID = 64;
    private static final int MAX_TEXT = 64;
    private static final int MAX_VALUE = 256;
    private static final int MAX_DISCS = 8;
    private static final int MAX_SETTINGS = 64;
    private static final int SHA256_HEX = 64;
    private static final int COMMIT_HEX = 40;

    private ModSetValidator() {}

    /**
     * Inflates and checks a mod set.
     * @param body The compressed set, as the launcher sent it.
     * @return The hash of its canonical form: two sets that differ only in date share it.
     * @throws InvalidModSetException If the set is damaged, too large or breaks the schema.
     */

    public static String check(byte[] body) throws InvalidModSetException {
        JsonObject set = parse(inflate(body));

        checkSet(set);

        return hash(set);
    }

    /**
     * Inflates at most the largest set allowed, so a small body cannot expand without bound.
     * @param body The compressed bytes.
     * @return The JSON text.
     * @throws InvalidModSetException If the bytes are not zlib data or expand too far.
     */

    private static String inflate(byte[] body) throws InvalidModSetException {
        Inflater inflater = new Inflater();
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];

        inflater.setInput(body);

        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(chunk);

                // Truncated input never finishes, and it never produces output either.
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw new InvalidModSetException("cut short");

                text.write(chunk, 0, count);

                // The cap holds whatever the compressed size was.
                if (text.size() > Constants.MODSET_MAX_JSON_BYTES) throw new InvalidModSetException("too large");
            }
        } catch (DataFormatException exception) {
            throw new InvalidModSetException("not compressed");
        } finally {
            inflater.end();
        }

        return text.toString(StandardCharsets.UTF_8);
    }

    /**
     * Reads the JSON strictly: the parser's lenient default takes things the launcher would not.
     * @param text The JSON text.
     * @return The set's object.
     * @throws InvalidModSetException If the text is not one JSON object.
     */

    private static JsonObject parse(String text) throws InvalidModSetException {
        try {
            JsonReader reader = new JsonReader(new StringReader(text));

            reader.setStrictness(Strictness.STRICT);

            JsonElement element = JsonParser.parseReader(reader);

            // A set is one object at its top, with nothing after it.
            if (!element.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw new InvalidModSetException("not an object");

            return element.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException | IOException exception) {
            throw new InvalidModSetException("not JSON");
        }
    }

    /**
     * Checks the set's own keys, then each list it holds.
     * @param set The set.
     * @throws InvalidModSetException If it breaks the schema.
     */

    private static void checkSet(JsonObject set) throws InvalidModSetException {
        requireKeys(set, SET_KEYS, "set");

        JsonElement format = set.get("format");

        // A newer format is for a newer store.
        if (format == null || !format.isJsonPrimitive() || !format.getAsJsonPrimitive().isNumber() || format.getAsInt() != 1)
            throw new InvalidModSetException("format");

        requireText(set, "name", MAX_TEXT, true);
        requireText(set, "created", MAX_TEXT, true);
        requireMatch(set, "game", ID, MAX_ID, true);
        requireText(set, "openrac", MAX_TEXT, true);
        requireMatch(set, "digest", HEX, MAX_TEXT, false);

        JsonArray discs = requireList(set, "discs", MAX_DISCS);

        for (JsonElement disc : discs) {
            // Each disc is a short name, nothing more.
            if (!isText(disc) || !ID.matcher(disc.getAsString()).matches() || disc.getAsString().length() > MAX_ID)
                throw new InvalidModSetException("discs");
        }

        for (JsonElement mod : requireList(set, "mods", Constants.MODSET_MAX_MODS)) checkMod(mod);
        for (JsonElement mod : requireList(set, "extras", Constants.MODSET_MAX_MODS)) checkMod(mod);
        for (JsonElement mod : requireList(set, "manual", Constants.MODSET_MAX_MODS)) checkManual(mod);
        for (JsonElement pack : requireList(set, "packs", Constants.MODSET_MAX_MODS)) checkPack(pack);
    }

    /**
     * Checks a mod a code can install: pinned to a release's checksums or to a commit.
     * @param element The mod's entry.
     * @throws InvalidModSetException If it breaks the schema.
     */

    private static void checkMod(JsonElement element) throws InvalidModSetException {
        JsonObject mod = requireObject(element, "mod");

        requireKeys(mod, MOD_KEYS, "mod");
        requireMatch(mod, "id", ID, MAX_ID, true);
        requireMatch(mod, "version", VERSION, MAX_TEXT, true);
        requireText(mod, "kind", MAX_TEXT, true);

        // Only the four kinds a manifest can declare.
        if (!KINDS.contains(mod.get("kind").getAsString())) throw new InvalidModSetException("kind");

        JsonElement sums = mod.get("sums");

        // A hash has exactly one length.
        if (sums != null && (!isText(sums) || sums.getAsString().length() != SHA256_HEX || !HEX.matcher(sums.getAsString()).matches()))
            throw new InvalidModSetException("sums");

        JsonObject source = requireObject(mod.get("source"), "source");

        requireKeys(source, SOURCE_KEYS, "source");
        requireMatch(source, "url", URL, MAX_VALUE, true);
        requireMatch(source, "tag", TAG, MAX_TEXT, false);

        JsonElement path = source.get("path");

        // The empty path is the repository's top.
        if (path != null && (!isText(path) || !isSafePath(path.getAsString()))) throw new InvalidModSetException("path");

        JsonElement commit = source.get("commit");

        // A commit is the full hash, which pins the files by itself.
        if (commit != null && (!isText(commit) || commit.getAsString().length() != COMMIT_HEX || !HEX.matcher(commit.getAsString()).matches()))
            throw new InvalidModSetException("commit");

        // Without either pin, a code would mean whatever is newest.
        if (sums == null && commit == null) throw new InvalidModSetException("unpinned");

        checkSettings(mod.get("settings"));
    }

    /**
     * Checks a mod's settings: plain values, none of them an address.
     * @param element The settings, or null when the mod has none.
     * @throws InvalidModSetException If it breaks the schema.
     */

    private static void checkSettings(JsonElement element) throws InvalidModSetException {
        if (element == null) return;

        JsonObject settings = requireObject(element, "settings");

        // A manifest declares a handful, not thousands.
        if (settings.size() > MAX_SETTINGS) throw new InvalidModSetException("settings");

        for (Map.Entry<String, JsonElement> setting : settings.entrySet()) {
            // Keys follow the manifest's rule.
            if (!KEY.matcher(setting.getKey()).matches() || setting.getKey().length() > MAX_ID) throw new InvalidModSetException("setting key");

            // Objects and lists are no setting's value.
            if (!setting.getValue().isJsonPrimitive()) throw new InvalidModSetException("setting value");

            JsonPrimitive value = setting.getValue().getAsJsonPrimitive();

            // Long strings or addresses would turn the store into a paste bin.
            if (value.isString() && (value.getAsString().length() > MAX_VALUE || value.getAsString().contains("://")))
                throw new InvalidModSetException("setting value");
        }
    }

    /**
     * Checks a mod the code only names: it has no catalogue source.
     * @param element The mod's entry.
     * @throws InvalidModSetException If it breaks the schema.
     */

    private static void checkManual(JsonElement element) throws InvalidModSetException {
        JsonObject mod = requireObject(element, "manual");

        requireKeys(mod, MANUAL_KEYS, "manual");
        requireMatch(mod, "id", ID, MAX_ID, true);
        requireMatch(mod, "version", VERSION, MAX_TEXT, true);
        requireText(mod, "name", MAX_TEXT, true);
    }

    /**
     * Checks a data pack the code only names.
     * @param element The pack's entry.
     * @throws InvalidModSetException If it breaks the schema.
     */

    private static void checkPack(JsonElement element) throws InvalidModSetException {
        JsonObject pack = requireObject(element, "pack");

        requireKeys(pack, PACK_KEYS, "pack");
        requireMatch(pack, "id", ID, MAX_ID, true);
        requireMatch(pack, "digest", HEX, MAX_TEXT, false);
    }

    /**
     * Hashes the set with its keys sorted and its date left out, the same set whenever it was made.
     * @param set The checked set.
     * @return The hash in lower-case hex.
     */

    private static String hash(JsonObject set) {
        JsonObject copy = set.deepCopy();

        copy.remove("created");

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(digest.digest(canonical(copy).toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Copies a value with every object's keys in sorted order.
     * @param element The value.
     * @return The sorted copy.
     */

    private static JsonElement canonical(JsonElement element) {
        if (element.isJsonArray()) {
            JsonArray list = new JsonArray();

            for (JsonElement item : element.getAsJsonArray()) list.add(canonical(item));

            return list;
        }

        // Numbers, strings and booleans have no keys to sort.
        if (!element.isJsonObject()) return element;

        JsonObject sorted = new JsonObject();

        for (Map.Entry<String, JsonElement> entry : new TreeMap<>(element.getAsJsonObject().asMap()).entrySet())
            sorted.add(entry.getKey(), canonical(entry.getValue()));

        return sorted;
    }

    /**
     * @param path A folder in a repository.
     * @return Whether it stays inside the repository.
     */

    private static boolean isSafePath(String path) {
        if (path.isEmpty()) return true;

        // The pattern allows dots, so the climbing names are refused here.
        for (String part : path.split("/")) if (part.equals(".") || part.equals("..")) return false;

        return PATH.matcher(path).matches() && path.length() <= MAX_VALUE;
    }

    /**
     * @param element A JSON value, or null.
     * @return Whether it is a string.
     */

    private static boolean isText(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    /**
     * Refuses any key the format does not have.
     * @param object The object.
     * @param allowed The keys it may have.
     * @param what What it is, for the error.
     * @throws InvalidModSetException If it has another key.
     */

    private static void requireKeys(JsonObject object, Set<String> allowed, String what) throws InvalidModSetException {
        for (String key : object.keySet()) if (!allowed.contains(key)) throw new InvalidModSetException(what + " key");
    }

    /**
     * @param element A JSON value, or null.
     * @param what What it should be, for the error.
     * @return The value as an object.
     * @throws InvalidModSetException If it is not one.
     */

    private static JsonObject requireObject(JsonElement element, String what) throws InvalidModSetException {
        if (element == null || !element.isJsonObject()) throw new InvalidModSetException(what);

        return element.getAsJsonObject();
    }

    /**
     * @param object The object holding the list.
     * @param key The list's key.
     * @param most The most items allowed.
     * @return The list, empty when it is left out.
     * @throws InvalidModSetException If it is no list, or too long.
     */

    private static JsonArray requireList(JsonObject object, String key, int most) throws InvalidModSetException {
        JsonElement element = object.get(key);

        if (element == null) return new JsonArray();

        if (!element.isJsonArray() || element.getAsJsonArray().size() > most) throw new InvalidModSetException(key);

        return element.getAsJsonArray();
    }

    /**
     * @param object The object holding the text.
     * @param key The key.
     * @param most The longest it may be.
     * @param required Whether it must be there.
     * @throws InvalidModSetException If it breaks those rules, or holds an address.
     */

    private static void requireText(JsonObject object, String key, int most, boolean required) throws InvalidModSetException {
        JsonElement element = object.get(key);

        if (element == null && !required) return;

        // Free text never holds an address: a code does not carry links.
        if (!isText(element) || element.getAsString().length() > most || element.getAsString().contains("://"))
            throw new InvalidModSetException(key);
    }

    /**
     * @param object The object holding the text.
     * @param key The key.
     * @param pattern What it must look like.
     * @param most The longest it may be.
     * @param required Whether it must be there.
     * @throws InvalidModSetException If it breaks those rules.
     */

    private static void requireMatch(JsonObject object, String key, Pattern pattern, int most, boolean required) throws InvalidModSetException {
        JsonElement element = object.get(key);

        if (element == null && !required) return;

        if (!isText(element) || element.getAsString().length() > most || !pattern.matcher(element.getAsString()).matches())
            throw new InvalidModSetException(key);
    }
}
