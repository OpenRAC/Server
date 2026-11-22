package com.openrac.server.hub;

import java.util.Locale;

/**
 * What a server process runs: a region that registers with a hub, or a region and its own hub.
 */

public enum Mode {
    ALL, // A region and the hub it registers with, in one process.
    REGION; // Lobbies and game rooms, registered with the hub at HUB_ADDRESS.

    /**
     * Reads a mode from the setting.
     * @param name The setting's value, in any case.
     * @return The mode, or REGION for a value that names none.
     */

    public static Mode get(String name) {
        for (Mode mode : values()) {
            // Settings are written in lower case, but any case reads.
            if (mode.name().equalsIgnoreCase(name.strip())) return mode;
        }

        return REGION;
    }

    /**
     * @return The name as the setting writes it.
     */

    public String getName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
