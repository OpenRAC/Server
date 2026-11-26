package com.openrac.net.packets.model;

import com.openrac.net.limits.RateLimit;

import java.util.function.Function;

/**
 * One packet a service receives, and what it does with it. A service registers one subclass per
 * packet id in its router, which checks {@link #isStrict()} and the rate limit before it calls
 * {@link #handle(Object, InputBuffer)}.
 * @param <C> The service's connection type.
 */

public abstract class IncomingPacket<C> {
    private final boolean strict;
    private final Function<C, RateLimit> limit;

    /**
     * Creates a packet with no rate limit.
     * @param strict Whether the connection must be introduced before it may send this packet.
     */

    protected IncomingPacket(boolean strict) {
        this(strict, null);
    }

    /**
     * Creates a packet that is limited per connection.
     * @param strict Whether the connection must be introduced before it may send this packet.
     * @param limit Picks the connection's bucket for this packet, which may answer null for none.
     */

    protected IncomingPacket(boolean strict, Function<C, RateLimit> limit) {
        this.strict = strict;
        this.limit = limit;
    }

    /**
     * Runs the packet.
     * @param connection The connection that sent it.
     * @param payload The rest of the packet after its id.
     */

    public abstract void handle(C connection, InputBuffer payload);

    /**
     * @return Whether the connection must be introduced before it may send this packet.
     */

    public boolean isStrict() {
        return strict;
    }

    /**
     * @param connection The connection that sent the packet.
     * @return The bucket this packet draws from on that connection, or null when it is not limited.
     */

    public RateLimit getRateLimit(C connection) {
        // Packets without a limit are never refused for their rate.
        return limit == null ? null : limit.apply(connection);
    }
}
