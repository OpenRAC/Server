package com.openrac.server.net.packets.game.in;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.server.Constants;
import com.openrac.server.hub.HubController;
import com.openrac.server.net.Connection;
import com.openrac.server.net.packets.model.IncomingPacket;
import com.openrac.utils.Utils;

/**
 * Proves a player's hub login: the hub says whose session the token is.
 */

public class IncomingAccountPacket extends IncomingPacket {
    /**
     * Creates the packet; a token is proved once, so it shares the party limit.
     */

    public IncomingAccountPacket() {
        super(true, Connection::getPartyLimit);
    }

    /**
     * Reads the opcode and runs it.
     * @param connection The connection that sent the token.
     * @param payload The account packet's payload.
     */

    @Override
    public void handle(Connection connection, InputBuffer payload) {
        // Logins happen at the hub, so a server only checks tokens.
        if (Opcodes.Account.get(payload.readUnsignedByte()) != Opcodes.Account.TOKEN) {
            connection.reject("sent an account opcode only the hub takes");
            return;
        }

        handleToken(connection, payload);
    }

    /**
     * Asks the hub whose session the token is.
     * @param connection The connection that sent the token.
     * @param payload The rest of the packet.
     */

    private void handleToken(Connection connection, InputBuffer payload) {
        String token = payload.readString();

        // A token past the limit is no token the hub gave out.
        if (!Utils.isValid(token, Constants.MAX_TOKEN_LENGTH)) {
            connection.reject("sent an invalid token");
            return;
        }

        HubController.verify(connection.getSession(), token);
    }
}
