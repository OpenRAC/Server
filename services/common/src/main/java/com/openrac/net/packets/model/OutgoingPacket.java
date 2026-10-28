package com.openrac.net.packets.model;

/**
 * Base class for every outgoing packet. It writes the packet id and an optional opcode, and the
 * subclass appends its payload in its constructor. The frame encoder adds the length.
 */

public class OutgoingPacket extends OutputBuffer {
    /**
     * Writes a packet id with no opcode.
     * @param id The ID of the packet.
     */

    public OutgoingPacket(int id) {
        this(id, -1);
    }

    /**
     * Writes the packet id, then the opcode when one is given.
     * @param id The ID of the packet.
     * @param opcode The opcode to write, or -1 to omit it.
     */

    public OutgoingPacket(int id, int opcode) {
        writeUnsignedByte(id);

        // Packets without opcodes pass -1.
        if (opcode > -1) writeUnsignedByte(opcode);
    }
}
