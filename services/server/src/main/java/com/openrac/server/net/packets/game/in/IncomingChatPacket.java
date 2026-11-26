package com.openrac.server.net.packets.game.in;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.ChatPacket;
import com.openrac.net.packets.out.ErrorPacket;
import com.openrac.server.Constants;
import com.openrac.server.Session;
import com.openrac.server.net.Connection;
import com.openrac.server.net.packets.model.IncomingPacket;
import com.openrac.server.party.Party;
import com.openrac.utils.Utils;
import com.openrac.utils.logger.Log;

/**
 * Sends a chat message to every member of the sender's party, the sender included.
 */

public class IncomingChatPacket extends IncomingPacket {
    /**
     * Creates the packet, limited like chat.
     */

    public IncomingChatPacket() {
        super(true, Connection::getChatLimit);
    }

    /**
     * Broadcasts the message inside the party.
     * @param connection The connection that sent the message.
     * @param payload The chat packet's payload.
     */

    @Override
    public void handle(Connection connection, InputBuffer payload) {
        String message = payload.readString();

        // Blank or oversized messages only come from a broken client.
        if (!Utils.isValid(message, Constants.MAX_CHAT_LENGTH)) {
            connection.reject("sent an invalid chat message");
            return;
        }

        Session session = connection.getSession();
        Party party = session.getParty();

        // Chat only travels inside a party.
        if (party == null) {
            session.send(new ErrorPacket(Opcodes.Error.NOT_IN_PARTY));
            return;
        }

        Log.debug("Chat from session " + session.getId() + " in party " + party.getCode() + ": " + message);

        party.broadcast(new ChatPacket(session.getId(), message));
    }
}
