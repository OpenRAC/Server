package com.openrac.hub.net.packets.model;

import com.openrac.hub.net.Connection;

/**
 * A packet a client or a game server sends the hub. Clients' packets draw from their connection's
 * request bucket; a game server's connection has none.
 */

public abstract class IncomingPacket extends com.openrac.net.packets.model.IncomingPacket<Connection> {
    /**
     * Creates a packet limited like every client request.
     * @param strict Whether the connection must have said hello or registered first.
     */

    protected IncomingPacket(boolean strict) {
        super(strict, Connection::getRequestLimit);
    }
}
