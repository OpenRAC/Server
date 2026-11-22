package com.openrac.net.packets.out;

import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;

/**
 * Opaque game data between any two members of a party, for games whose players all talk to each
 * other rather than through one host. Both directions share one layout: a session id, then the
 * payload up to the end of the frame.
 */

public class RelayPacket extends OutgoingPacket {
    /**
     * Creates a relayed data packet.
     * @param sessionId To the server, the target (or broadcast). From it, the sender.
     * @param payload The game's bytes.
     */

    public RelayPacket(int sessionId, byte[] payload) {
        super(Packets.RELAY.ordinal());

        writeInt(sessionId);
        writeBytes(payload);
    }
}
