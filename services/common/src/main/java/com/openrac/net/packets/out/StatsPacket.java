package com.openrac.net.packets.out;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.model.OutputBuffer;

/**
 * Asking the hub for stats and ladders, storing the game's own, and the hub's answers.
 */

public class StatsPacket extends OutgoingPacket {
    /**
     * A stats opcode with no payload: the hub's answer to a write.
     * @param opcode The stats opcode.
     */

    public StatsPacket(Opcodes.Stats opcode) {
        super(Packets.STATS.ordinal(), opcode.ordinal());
    }

    /**
     * A stats opcode with its fields, as the README's STATS table lays them out.
     * @param opcode The stats opcode.
     * @param data The request or answer fields.
     */

    public StatsPacket(Opcodes.Stats opcode, OutputBuffer data) {
        this(opcode);

        append(data);
    }
}
