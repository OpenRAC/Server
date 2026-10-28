package com.openrac.net.packets.out;

import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;

/**
 * Clock synchronisation. Every player stamps game data against the server's clock, so a client
 * sends its own time and gets it back beside the server's, from which it estimates the offset and
 * the round trip.
 */

public class TimePacket extends OutgoingPacket {
    /**
     * Client to server: when the request left the client.
     * @param clientTime The client's clock, in milliseconds.
     */

    public TimePacket(long clientTime) {
        super(Packets.TIME.ordinal());

        writeLong(clientTime);
    }

    /**
     * Server to client: the request's own time, echoed, and the server's clock when it answered.
     * @param clientTime The client's clock from the request, in milliseconds.
     * @param serverTime The server's clock, in milliseconds.
     */

    public TimePacket(long clientTime, long serverTime) {
        super(Packets.TIME.ordinal());

        writeLong(clientTime);
        writeLong(serverTime);
    }
}
