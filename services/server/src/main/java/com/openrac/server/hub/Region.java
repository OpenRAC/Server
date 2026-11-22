package com.openrac.server.hub;

import com.openrac.net.packets.model.OutputBuffer;

/**
 * A region the hub lists: a server that runs lobbies and game rooms, where players go after the
 * hub. The hub's own region, in single-process mode, has no session behind it.
 */

public class Region {
    private final String name;
    private final String host; // Empty: the address the player reached the hub on.
    private final int port;
    private volatile int players; // As the region last reported.

    /**
     * Creates a region.
     * @param name The name players pick it by.
     * @param host The address players reach it on, empty for the hub's.
     * @param port Its port.
     * @param players How many players it has.
     */

    public Region(String name, String host, int port, int players) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.players = players;
    }

    /**
     * @return The name players pick it by.
     */

    public String getName() {
        return name;
    }

    /**
     * @param players How many players the region has now.
     */

    public void setPlayers(int players) {
        this.players = players;
    }

    /**
     * Writes the region as the region list shows it.
     * @return Its name, host, port and players.
     */

    public OutputBuffer serialize() {
        OutputBuffer buffer = new OutputBuffer();

        buffer.writeString(name);
        buffer.writeString(host);
        buffer.writeInt(port);
        buffer.writeInt(players);

        return buffer;
    }
}
