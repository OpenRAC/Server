package com.openrac.server;

/**
 * Limits and defaults this server decides on its own.
 */

public class Constants {
    public static final String NAME = "OpenRAC Server";
    public static final String VERSION = "1.0.0";

    public static final int DEFAULT_PORT = 7770;
    public static final String PORT_ENV = "OPENRAC_PORT"; // Environment variable that overrides the default port.

    public static final int MAX_VERSION_LENGTH = 32;
    public static final int MAX_NAME_LENGTH = 32;
    public static final int MAX_CHAT_LENGTH = 256;

    public static final int MAX_PARTY_SIZE = 4; // Host included.

    // Rate limits per connection: a burst, then the steady rate per second.
    public static final int CHAT_BURST = 5;
    public static final double CHAT_PER_SECOND = 1;
    public static final int PARTY_BURST = 5;
    public static final double PARTY_PER_SECOND = 1;
    public static final int DATA_BURST = 120; // 30 Hz state updates plus events.
    public static final double DATA_PER_SECOND = 60;
    public static final int TIME_BURST = 5;
    public static final double TIME_PER_SECOND = 2;

    // Dropped packets a connection may run up before it is closed.
    public static final int STRIKE_BURST = 20;
    public static final double STRIKES_FORGIVEN_PER_SECOND = 1;

    public static final int MAX_CONNECTIONS_PER_ADDRESS = 8;
    public static final int HELLO_TIMEOUT_SECONDS = 10; // A connection says hello within this, or is closed.
    public static final int CODE_LENGTH = 6;
    public static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // No 0, O, 1 or I, which read alike.
}
