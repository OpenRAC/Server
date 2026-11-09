package com.openrac.net.packets.out;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;

/**
 * Server to client: why a request was refused.
 */

public class ErrorPacket extends OutgoingPacket {
    /**
     * Creates an error packet.
     * @param reason Why the request was refused.
     */

    public ErrorPacket(Opcodes.Error reason) {
        super(Packets.ERROR.ordinal(), reason.ordinal());
    }

    /**
     * Creates an error packet with a word after its reason: OTHER_GAME with the party's family.
     * @param reason Why the request was refused.
     * @param detail The string that follows the reason.
     */

    public ErrorPacket(Opcodes.Error reason, String detail) {
        this(reason);

        writeString(detail);
    }
}
