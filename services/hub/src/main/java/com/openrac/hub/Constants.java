package com.openrac.hub;

import com.openrac.system.Environment;

/**
 * Limits and defaults the hub decides on its own, read from the environment once it is loaded
 * (.env.defaults at the repository root, overridden by a local .env).
 */

public class Constants {
    public static final String NAME = Environment.getString("HUB_NAME", "OpenRAC Hub");
    public static final String VERSION = "1.0.0";

    public static final String HOST = Environment.getString("HOST", "0.0.0.0");
    public static final int PORT = Environment.getInt("HUB_PORT", 7177);

    public static final String REGION_KEY = Environment.getString("REGION_KEY", ""); // Empty: any server may register.
    public static final int MAX_SERVERS = 32; // Fits the REGIONS count byte with room to spare.
    public static final int SERVER_TIMEOUT_SECONDS = Environment.getInt("SERVER_TIMEOUT_SECONDS", 20); // Silence that drops a server.

    public static final int MAX_NAME_LENGTH = 32;
    public static final int MAX_VERSION_LENGTH = 32;
    public static final int MAX_TOKEN_LENGTH = 64;

    public static final int HELLO_TIMEOUT_SECONDS = Environment.getInt("HELLO_TIMEOUT_SECONDS", 10); // Hello or register within this.
    public static final int MAX_CONNECTIONS_PER_ADDRESS = Environment.getInt("MAX_CONNECTIONS_PER_ADDRESS", 8);

    // Requests per connection: a burst, then the steady rate per second.
    public static final int REQUEST_BURST = Environment.getInt("HUB_REQUEST_BURST", 10);
    public static final int REQUEST_PER_SECOND = Environment.getInt("HUB_REQUEST_PER_SECOND", 2);

    // Account names and passwords, as the game's login screen takes them.
    public static final int MAX_ACCOUNT_NAME_LENGTH = 16;
    public static final int MIN_PASSWORD_LENGTH = Environment.getInt("MIN_PASSWORD_LENGTH", 4);
    public static final int MAX_PASSWORD_LENGTH = 32; // Well under the hash's 72 byte limit.
    public static final int PASSWORD_COST = Environment.getInt("PASSWORD_COST", 10); // Hash rounds, as a power of two.

    // Wrong passwords one address may send before it waits out the window.
    public static final int LOGIN_FAILURES = Environment.getInt("LOGIN_FAILURES", 10);
    public static final int LOGIN_WINDOW_SECONDS = Environment.getInt("LOGIN_WINDOW_SECONDS", 600);

    public static final int SESSION_DAYS = Environment.getInt("SESSION_DAYS", 30); // A login's token lasts this long.
    public static final int TOKEN_BYTES = 32;
    public static final int ACCOUNT_THREADS = Environment.getInt("ACCOUNT_THREADS", 4); // Hashing and database work.

    // PostgreSQL. An empty URL keeps the accounts in memory, lost on restart.
    public static final String DATABASE_URL = Environment.getString("DATABASE_URL", "");
    public static final String DATABASE_USER = Environment.getString("DATABASE_USER", "openrac");
    public static final String DATABASE_PASSWORD = Environment.getString("DATABASE_PASSWORD", "");
    public static final int DATABASE_POOL_SIZE = Environment.getInt("DATABASE_POOL_SIZE", 8);
}
