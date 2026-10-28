package com.openrac.net.packets.out;

import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;

/**
 * Server to client, right after hello: the token that binds the session's UDP address, and the
 * port the server takes datagrams on. A client that never sends one keeps all traffic on TCP.
 */

public class UdpPacket extends OutgoingPacket {
    /**
     * Creates a UDP offer.
     * @param token The session's secret datagram token.
     * @param port The server's UDP port.
     */

    public UdpPacket(long token, int port) {
        super(Packets.UDP.ordinal());

        writeLong(token);
        writeInt(port);
    }
}
