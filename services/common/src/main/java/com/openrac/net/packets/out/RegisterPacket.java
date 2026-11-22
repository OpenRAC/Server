package com.openrac.net.packets.out;

import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;

/**
 * A region server telling its hub it is there, and how many players it has: sent on connecting and
 * then every few seconds.
 */

public class RegisterPacket extends OutgoingPacket {
    /**
     * Creates a registration.
     * @param key The hub's region key, empty when it takes any region.
     * @param name The region's name in the list.
     * @param host The address players reach it on, empty for the one the hub sees.
     * @param port The region's port.
     * @param players How many players it has now.
     */

    public RegisterPacket(String key, String name, String host, int port, int players) {
        super(Packets.REGISTER.ordinal());

        writeString(key);
        writeString(name);
        writeString(host);
        writeInt(port);
        writeInt(players);
    }
}
