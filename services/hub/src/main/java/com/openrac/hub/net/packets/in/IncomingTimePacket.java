package com.openrac.hub.net.packets.in;

import com.openrac.hub.net.Connection;
import com.openrac.hub.net.packets.model.IncomingPacket;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.TimePacket;
import com.openrac.utils.Clock;

/**
 * Answers a clock request with the peer's own time and the hub's.
 */

public class IncomingTimePacket extends IncomingPacket {
    /**
     * Creates the packet; only an introduced connection may ask.
     */

    public IncomingTimePacket() {
        super(true);
    }

    /**
     * Sends the two times back.
     * @param connection The connection that asked.
     * @param payload The time packet's payload.
     */

    @Override
    public void handle(Connection connection, InputBuffer payload) {
        connection.getContext().writeAndFlush(new TimePacket(payload.readLong(), Clock.now()));
    }
}
