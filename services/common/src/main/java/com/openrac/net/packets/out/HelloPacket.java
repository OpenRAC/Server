package com.openrac.net.packets.out;

import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;

/**
 * The greeting that opens every connection. The client introduces itself, and the server answers
 * with the session id it assigned.
 */

public class HelloPacket extends OutgoingPacket {
    /**
     * Client to server: who is connecting.
     * @param version The client's version.
     * @param name The player's display name.
     */

    public HelloPacket(String version, String name) {
        super(Packets.HELLO.ordinal());

        writeString(version);
        writeString(name);
    }

    /**
     * Client to server: who is connecting, and what it can do.
     * @param version The client's version.
     * @param name The player's display name.
     * @param features The client's bits.
     */

    public HelloPacket(String version, String name, int features) {
        this(version, name);

        writeUnsignedByte(features);
    }

    /**
     * Server to client: the session id assigned to the connection.
     * @param sessionId The new session id.
     */

    public HelloPacket(int sessionId) {
        super(Packets.HELLO.ordinal());

        writeInt(sessionId);
    }
}
