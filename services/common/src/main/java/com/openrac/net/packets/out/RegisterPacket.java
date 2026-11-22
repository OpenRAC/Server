package com.openrac.net.packets.out;

import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;

/**
 * A game server telling the hub it is there: the first packet on its link, before any STATUS.
 */

public class RegisterPacket extends OutgoingPacket {
    /**
     * Creates a registration.
     * @param key The hub's region key, empty when it takes any region.
     * @param name The region's name in the list.
     * @param host The address players reach it on, empty for the one the hub sees.
     * @param port The region's port.
     * @param players How many players it has now.
     * @param server The server's own name, which tells one region's servers apart.
     */

    public RegisterPacket(String key, String name, String host, int port, int players, String server) {
        super(Packets.REGISTER.ordinal());

        writeString(key);
        writeString(name);
        writeString(host);
        writeInt(port);
        writeInt(players);
        writeString(server);
    }
}
