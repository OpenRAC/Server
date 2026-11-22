package com.openrac.server;

import com.openrac.net.NetworkConstants;
import com.openrac.system.Environment;

/**
 * Limits and defaults this server decides on its own. Most come from the environment
 * (.env.defaults at the repository root, overridden by a local .env), read once it is loaded.
 */

public class Constants {
    public static final String NAME = Environment.getString("NAME", "OpenRAC Server");
    public static final String VERSION = "1.0.0";

    public static final String HOST = Environment.getString("HOST", "0.0.0.0");
    public static final int PORT = Environment.getInt("PORT", 7770);
    public static final int HTTP_PORT = Environment.getInt("HTTP_PORT", 7771); // The mod set store.

    public static final int MAX_VERSION_LENGTH = 32;

    // What the UDP offer says this server takes.
    public static final int FEATURES = NetworkConstants.FEATURE_KICK | NetworkConstants.FEATURE_RESUME | NetworkConstants.FEATURE_ADVERT
            | NetworkConstants.FEATURE_RELAY | NetworkConstants.FEATURE_UI;

    public static final int MAX_NAME_LENGTH = 32;
    public static final int MAX_CHAT_LENGTH = 256;
    public static final int MAX_FAMILY_LENGTH = 32;
    public static final int MAX_UI_KEY_LENGTH = 32; // A UI action key, an identifier of the description.
    public static final int MAX_DESCRIPTION_LENGTH = Environment.getInt("MAX_DESCRIPTION_LENGTH", 48);
    public static final int MAX_ADVERT_LENGTH = Environment.getInt("MAX_ADVERT_LENGTH", 512); // Bytes a host may list its party with.

    public static final int MAX_PARTY_SIZE = Environment.getInt("MAX_PARTY_SIZE", 4); // Host included.
    public static final int MAX_LISTED_PARTIES = Environment.getInt("MAX_LISTED_PARTIES", 32); // Fits the count byte.

    // The game family of a request that names none: every client from before families is a
    // Ratchet & Clank 1 game, whose PAL and NTSC discs share one world (openrac_api.world_family).
    public static final String DEFAULT_FAMILY = "rac1-60-1";

    // Rate limits per connection: a burst, then the steady rate per second.
    public static final int CHAT_BURST = Environment.getInt("CHAT_BURST", 5);
    public static final int CHAT_PER_SECOND = Environment.getInt("CHAT_PER_SECOND", 1);
    public static final int PARTY_BURST = Environment.getInt("PARTY_BURST", 5);
    public static final int PARTY_PER_SECOND = Environment.getInt("PARTY_PER_SECOND", 1);
    public static final int DATA_BURST = Environment.getInt("DATA_BURST", 120); // 30 Hz state updates plus events.
    public static final int DATA_PER_SECOND = Environment.getInt("DATA_PER_SECOND", 60);
    public static final int RELAY_BURST = Environment.getInt("RELAY_BURST", 240); // Every frame to each member, plus events.
    public static final int RELAY_PER_SECOND = Environment.getInt("RELAY_PER_SECOND", 120);
    public static final int TIME_BURST = Environment.getInt("TIME_BURST", 5);
    public static final int TIME_PER_SECOND = Environment.getInt("TIME_PER_SECOND", 2);
    public static final int UI_BURST = Environment.getInt("UI_BURST", 5);
    public static final int UI_PER_SECOND = Environment.getInt("UI_PER_SECOND", 1);

    // Dropped packets a connection may run up before it is closed.
    public static final int STRIKE_BURST = Environment.getInt("STRIKE_BURST", 20);
    public static final int STRIKES_FORGIVEN_PER_SECOND = Environment.getInt("STRIKES_FORGIVEN_PER_SECOND", 1);

    public static final int MAX_CONNECTIONS_PER_ADDRESS = Environment.getInt("MAX_CONNECTIONS_PER_ADDRESS", 8);
    public static final int UDP_TIMEOUT_SECONDS = Environment.getInt("UDP_TIMEOUT_SECONDS", 15); // Quiet UDP falls back to TCP.
    public static final int HELLO_TIMEOUT_SECONDS = Environment.getInt("HELLO_TIMEOUT_SECONDS", 10); // Hello within this, or closed.

    // A dropped player keeps its slot this long; a client that has not been heard from for the
    // idle time is taken for dropped, as a machine that dies closes nothing.
    public static final int RESUME_SECONDS = Environment.getInt("RESUME_SECONDS", 120);
    public static final int PARTY_IDLE_SECONDS = Environment.getInt("PARTY_IDLE_SECONDS", 5);
    public static final int HOST_GRACE_SECONDS = Environment.getInt("HOST_GRACE_SECONDS", 15); // Then the next host takes over.

    // Mod sets behind short codes: a request's size, and what the store keeps.
    public static final int MODSET_MAX_BODY_BYTES = Environment.getInt("MODSET_MAX_BODY_BYTES", 16_384); // Compressed.
    public static final int MODSET_MAX_JSON_BYTES = Environment.getInt("MODSET_MAX_JSON_BYTES", 65_536); // Inflated.
    public static final int MODSET_MAX_MODS = 40;
    public static final int MODSET_CAPACITY = Environment.getInt("MODSET_CAPACITY", 50_000);
    public static final int MODSET_TTL_DAYS = Environment.getInt("MODSET_TTL_DAYS", 30); // Refreshed by each read.
    public static final int MODSET_SAVE_MINUTES = Environment.getInt("MODSET_SAVE_MINUTES", 5);
    public static final String MODSET_FILE = Environment.getString("MODSET_FILE", "data/modsets.bin");
    public static final int MODSET_PUTS_PER_MINUTE = Environment.getInt("MODSET_PUTS_PER_MINUTE", 10); // Per address.
    public static final int MODSET_GETS_PER_MINUTE = Environment.getInt("MODSET_GETS_PER_MINUTE", 60); // Per address.
    public static final int HTTP_TIMEOUT_SECONDS = Environment.getInt("HTTP_TIMEOUT_SECONDS", 10); // Whole request, or closed.

    // Hosts' UI descriptions, one file per family or game.
    public static final String UI_DIRECTORY = Environment.getString("UI_DIRECTORY", "ui");

    public static final int MODSET_CODE_LENGTH = 8; // 40 bits of the alphabet below.
    public static final int CODE_LENGTH = 6;
    public static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // No 0, O, 1 or I, which read alike.
}
