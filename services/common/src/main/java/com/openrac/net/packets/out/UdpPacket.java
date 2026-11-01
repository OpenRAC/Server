package com.openrac.net.packets.out;

import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;

/**
 * Server to client, right after hello: the token that binds the session's UDP address, and the
 * port the server takes datagrams on. A client that never sends one keeps all traffic on TCP. The
 * feature bits after them say which requests this server takes, since an unknown request closes a
 * connection; clients from before them stop reading at the port. Last, the token that takes this
 * session back after its connection drops.
 */

public class UdpPacket extends OutgoingPacket {
    /**
     * Creates a UDP offer.
     * @param token The session's secret datagram token.
     * @param port The server's UDP port.
     * @param features The bits of what this server takes.
     * @param resumeToken The session's secret token for RESUME.
     */

    public UdpPacket(long token, int port, int features, long resumeToken) {
        super(Packets.UDP.ordinal());

        writeLong(token);
        writeInt(port);
        writeUnsignedByte(features);
        writeLong(resumeToken);
    }
}
