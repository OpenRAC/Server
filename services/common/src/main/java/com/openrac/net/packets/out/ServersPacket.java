package com.openrac.net.packets.out;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.model.OutputBuffer;

/**
 * The hub keeping a client's list of game servers current: every server once, then each change.
 */

public class ServersPacket extends OutgoingPacket {
    /**
     * WATCH from a client: which game family it plays.
     * @param family The client's game family.
     */

    public ServersPacket(String family) {
        super(Packets.SERVERS.ordinal(), Opcodes.Servers.WATCH.ordinal());

        writeString(family);
    }

    /**
     * REMOVED from the hub: a server that left the list.
     * @param serverId The server's id in the hub's list.
     */

    public ServersPacket(int serverId) {
        super(Packets.SERVERS.ordinal(), Opcodes.Servers.REMOVED.ordinal());

        writeInt(serverId);
    }

    /**
     * A servers opcode carrying serialized data: ADDED with a server, CHANGED with its counts.
     * @param opcode The servers opcode.
     * @param data The serialized server or counts.
     */

    public ServersPacket(Opcodes.Servers opcode, OutputBuffer data) {
        super(Packets.SERVERS.ordinal(), opcode.ordinal());

        append(data);
    }
}
