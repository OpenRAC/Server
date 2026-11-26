package com.openrac.hub.net.packets.in;

import com.openrac.hub.net.Connection;
import com.openrac.hub.net.packets.model.IncomingPacket;
import com.openrac.hub.servers.ServerController;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;

/**
 * Starts a client's watch on the server list.
 */

public class IncomingServersPacket extends IncomingPacket {
    /**
     * Creates the packet; only an introduced connection may ask.
     */

    public IncomingServersPacket() {
        super(true);
    }

    /**
     * Reads the opcode and runs it.
     * @param connection The connection that asked.
     * @param payload The servers packet's payload.
     */

    @Override
    public void handle(Connection connection, InputBuffer payload) {
        // Servers have no use for the list.
        if (!connection.isGuest(Packets.SERVERS)) return;

        // Only WATCH comes from a client. The family waits for filters.
        if (Opcodes.Servers.get(payload.readUnsignedByte()) != Opcodes.Servers.WATCH) {
            connection.reject("sent an invalid servers opcode");
            return;
        }

        ServerController.watch(connection.getChannel());
    }
}
