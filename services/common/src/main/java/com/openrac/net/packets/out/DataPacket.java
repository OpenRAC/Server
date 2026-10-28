package com.openrac.net.packets.out;

import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;

/**
 * Opaque game data the server forwards without reading. Both directions share one layout: a session
 * id, then the payload up to the end of the frame.
 */

public class DataPacket extends OutgoingPacket {
    /**
     * Creates a game data packet.
     * @param sessionId To the server, the target (or broadcast). From it, the sender.
     * @param payload The game's bytes.
     */

    public DataPacket(int sessionId, byte[] payload) {
        super(Packets.DATA.ordinal());

        writeInt(sessionId);
        writeBytes(payload);
    }
}
