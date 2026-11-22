package com.openrac.server.hub;

import java.util.Locale;

/**
 * What a server process runs: the hub, a region, or both in one process.
 */

public enum Mode {
    ALL, // The hub and one region of its own.
    HUB, // Greetings and the region list only.
    REGION; // Lobbies and game rooms, registered with a hub.

    /**
     * Reads a mode from the setting.
     * @param name The setting's value, in any case.
     * @return The mode, or ALL for a value that names none.
     */

    public static Mode get(String name) {
        for (Mode mode : values()) {
            // Settings are written in lower case, but any case reads.
            if (mode.name().equalsIgnoreCase(name.strip())) return mode;
        }

        return ALL;
    }

    /**
     * @return The name as the setting writes it.
     */

    public String getName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
