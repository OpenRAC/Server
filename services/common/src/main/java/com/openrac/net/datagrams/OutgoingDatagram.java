package com.openrac.net.datagrams;

import com.openrac.net.packets.model.OutputBuffer;

/**
 * One UDP datagram's bytes. A client's datagrams name its session and token ahead of the kind, so
 * the server can tell who sent them. The server's start with the kind, then the sender.
 */

public class OutgoingDatagram extends OutputBuffer {
    /**
     * Client to server: a datagram with no payload, BIND.
     * @param sessionId The session id from hello.
     * @param token The token from the UDP packet.
     * @param kind The datagram kind.
     */

    public OutgoingDatagram(int sessionId, long token, Datagrams kind) {
        writeInt(sessionId);
        writeLong(token);
        writeUnsignedByte(kind.ordinal());
    }

    /**
     * Client to server: game state for a target, STATE.
     * @param sessionId The session id from hello.
     * @param token The token from the UDP packet.
     * @param target The member's session id, or the broadcast target.
     * @param payload The game's bytes.
     */

    public OutgoingDatagram(int sessionId, long token, int target, byte[] payload) {
        this(sessionId, token, Datagrams.STATE);

        writeInt(target);
        writeBytes(payload);
    }

    /**
     * Server to client: game state from a member, STATE.
     * @param senderId The session that sent the state.
     * @param payload The game's bytes.
     */

    public OutgoingDatagram(int senderId, byte[] payload) {
        writeUnsignedByte(Datagrams.STATE.ordinal());
        writeInt(senderId);
        writeBytes(payload);
    }
}
