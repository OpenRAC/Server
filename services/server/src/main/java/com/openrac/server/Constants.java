package com.openrac.server;

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

    public static final int MAX_VERSION_LENGTH = 32;
    public static final int MAX_NAME_LENGTH = 32;
    public static final int MAX_CHAT_LENGTH = 256;

    public static final int MAX_PARTY_SIZE = Environment.getInt("MAX_PARTY_SIZE", 4); // Host included.

    // Rate limits per connection: a burst, then the steady rate per second.
    public static final int CHAT_BURST = Environment.getInt("CHAT_BURST", 5);
    public static final int CHAT_PER_SECOND = Environment.getInt("CHAT_PER_SECOND", 1);
    public static final int PARTY_BURST = Environment.getInt("PARTY_BURST", 5);
    public static final int PARTY_PER_SECOND = Environment.getInt("PARTY_PER_SECOND", 1);
    public static final int DATA_BURST = Environment.getInt("DATA_BURST", 120); // 30 Hz state updates plus events.
    public static final int DATA_PER_SECOND = Environment.getInt("DATA_PER_SECOND", 60);
    public static final int TIME_BURST = Environment.getInt("TIME_BURST", 5);
    public static final int TIME_PER_SECOND = Environment.getInt("TIME_PER_SECOND", 2);

    // Dropped packets a connection may run up before it is closed.
    public static final int STRIKE_BURST = Environment.getInt("STRIKE_BURST", 20);
    public static final int STRIKES_FORGIVEN_PER_SECOND = Environment.getInt("STRIKES_FORGIVEN_PER_SECOND", 1);

    public static final int MAX_CONNECTIONS_PER_ADDRESS = Environment.getInt("MAX_CONNECTIONS_PER_ADDRESS", 8);
    public static final int HELLO_TIMEOUT_SECONDS = Environment.getInt("HELLO_TIMEOUT_SECONDS", 10); // Hello within this, or closed.

    public static final int CODE_LENGTH = 6;
    public static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // No 0, O, 1 or I, which read alike.
}
