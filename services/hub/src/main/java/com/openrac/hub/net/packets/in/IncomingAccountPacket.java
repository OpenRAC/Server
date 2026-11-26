package com.openrac.hub.net.packets.in;

import com.openrac.hub.accounts.AccountController;
import com.openrac.hub.net.Connection;
import com.openrac.hub.net.packets.model.IncomingPacket;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;

/**
 * Logs a client in to its account, or out of it.
 */

public class IncomingAccountPacket extends IncomingPacket {
    /**
     * Creates the packet; only an introduced connection may ask.
     */

    public IncomingAccountPacket() {
        super(true);
    }

    /**
     * Reads the opcode and runs it.
     * @param connection The connection that sent the request.
     * @param payload The account packet's payload.
     */

    @Override
    public void handle(Connection connection, InputBuffer payload) {
        // Servers check tokens through VERIFY, never log in.
        if (!connection.isGuest(Packets.ACCOUNT)) return;

        switch (Opcodes.Account.get(payload.readUnsignedByte())) {
            case LOGIN -> AccountController.login(connection.getChannel(), connection.getAddress(), payload.readString(), payload.readString());
            case LOGOUT -> handleLogout(connection, payload);

            // TOKEN belongs to game servers, LOGGED_IN to the hub.
            case null, default -> connection.reject("sent an invalid account opcode");
        }
    }

    /**
     * Ends the client's login.
     * @param connection The connection that sent the request.
     * @param payload The rest of the packet after the opcode.
     */

    private void handleLogout(Connection connection, InputBuffer payload) {
        String token = connection.readToken(payload);

        // An invalid token already closed the connection.
        if (token != null) AccountController.logout(connection.getChannel(), token);
    }
}
