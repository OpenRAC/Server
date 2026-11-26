package com.openrac.server.net.packets.model;

import com.openrac.net.limits.RateLimit;
import com.openrac.server.net.Connection;

import java.util.function.Function;

/**
 * A packet a player's client sends this server. Every one but the greeting needs a session first.
 */

public abstract class IncomingPacket extends com.openrac.net.packets.model.IncomingPacket<Connection> {
    /**
     * Creates a packet with no rate limit.
     * @param strict Whether the client must have said hello first.
     */

    protected IncomingPacket(boolean strict) {
        super(strict);
    }

    /**
     * Creates a packet that is limited per connection.
     * @param strict Whether the client must have said hello first.
     * @param limit Picks the connection's bucket for this packet.
     */

    protected IncomingPacket(boolean strict, Function<Connection, RateLimit> limit) {
        super(strict, limit);
    }
}
