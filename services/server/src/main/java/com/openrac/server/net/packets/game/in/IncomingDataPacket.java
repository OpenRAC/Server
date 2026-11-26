package com.openrac.server.net.packets.game.in;

import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.DataPacket;
import com.openrac.server.Session;
import com.openrac.server.net.Connection;
import com.openrac.server.net.packets.model.IncomingPacket;
import com.openrac.server.party.Party;
import com.openrac.utils.logger.Log;

/**
 * Forwards game data along the party's links, unread.
 */

public class IncomingDataPacket extends IncomingPacket {
    /**
     * Creates the packet, limited like game data.
     */

    public IncomingDataPacket() {
        super(true, Connection::getDataLimit);
    }

    /**
     * Forwards the data to one member or to every other.
     * @param connection The connection that sent the data.
     * @param payload The data packet's payload.
     */

    @Override
    public void handle(Connection connection, InputBuffer payload) {
        int target = payload.readInt();
        byte[] data = payload.readRemainingBytes();

        Session session = connection.getSession();
        Party party = session.getParty();

        // Data sent outside a party has nowhere to go.
        if (party == null) return;

        // Game data flows every frame, so only build the line when shown.
        if (Log.isVerbose()) Log.verbose("Data from session " + session.getId() + " to " + target + " in party " + party.getCode() + ": " + data.length + " bytes.");

        party.forward(session, target, new DataPacket(session.getId(), data));
    }
}
