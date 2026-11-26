package com.openrac.hub.net.packets.in;

import com.openrac.hub.net.Connection;
import com.openrac.hub.net.packets.model.IncomingPacket;
import com.openrac.hub.servers.ServerController;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;

/**
 * Sends a client a snapshot of the live server list.
 */

public class IncomingRegionsPacket extends IncomingPacket {
    /**
     * Creates the packet; only an introduced connection may ask.
     */

    public IncomingRegionsPacket() {
        super(true);
    }

    /**
     * Answers with the list.
     * @param connection The connection that asked.
     * @param payload The regions packet's payload.
     */

    @Override
    public void handle(Connection connection, InputBuffer payload) {
        // A server asking for the list is closed by the check.
        if (connection.isGuest(Packets.REGIONS)) connection.getContext().writeAndFlush(ServerController.getRegionsPacket());
    }
}
