package com.openrac.server.net.packets.game.in;

import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.TimePacket;
import com.openrac.server.net.Connection;
import com.openrac.server.net.packets.model.IncomingPacket;
import com.openrac.utils.Clock;

/**
 * Answers a clock request with the client's own time and the server's.
 */

public class IncomingTimePacket extends IncomingPacket {
    /**
     * Creates the packet, limited like clock requests.
     */

    public IncomingTimePacket() {
        super(true, Connection::getTimeLimit);
    }

    /**
     * Sends the two times back.
     * @param connection The connection that asked.
     * @param payload The time packet's payload.
     */

    @Override
    public void handle(Connection connection, InputBuffer payload) {
        connection.getSession().send(new TimePacket(payload.readLong(), Clock.now()));
    }
}
