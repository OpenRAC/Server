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
}
