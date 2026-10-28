package com.openrac.net.packets.out;

import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;

/**
 * A chat message within a party.
 */

public class ChatPacket extends OutgoingPacket {
    /**
     * Client to server: a message for the party.
     * @param message The message text.
     */

    public ChatPacket(String message) {
        super(Packets.CHAT.ordinal());

        writeString(message);
    }

    /**
     * Server to client: a message from a member.
     * @param senderId The session that sent the message.
     * @param message The message text.
     */

    public ChatPacket(int senderId, String message) {
        super(Packets.CHAT.ordinal());

        writeInt(senderId);
        writeString(message);
    }
}
