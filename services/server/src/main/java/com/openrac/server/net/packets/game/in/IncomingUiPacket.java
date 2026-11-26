package com.openrac.server.net.packets.game.in;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.server.Constants;
import com.openrac.server.net.Connection;
import com.openrac.server.net.packets.model.IncomingPacket;
import com.openrac.server.ui.UiController;
import com.openrac.utils.Utils;

/**
 * Runs a request about the screens a game's multiplayer menus show.
 */

public class IncomingUiPacket extends IncomingPacket {
    /**
     * Creates the packet, limited like screen requests.
     */

    public IncomingUiPacket() {
        super(true, Connection::getUiLimit);
    }

    /**
     * Reads the opcode and runs it.
     * @param connection The connection that sent the request.
     * @param payload The UI packet's payload.
     */

    @Override
    public void handle(Connection connection, InputBuffer payload) {
        Opcodes.Ui opcode = Opcodes.Ui.get(payload.readUnsignedByte());

        switch (opcode) {
            case GET -> handleGet(connection, payload);
            case ACTION -> handleAction(connection, payload);

            // The rest are answers only the server sends.
            case null, default -> connection.reject("sent an invalid UI opcode");
        }
    }

    /**
     * Sends the description of a game's screens.
     * @param connection The connection that sent the request.
     * @param payload The rest of the request.
     */

    private void handleGet(Connection connection, InputBuffer payload) {
        String family = connection.readFamily(payload);

        if (family != null) UiController.handleGet(connection.getSession(), Connection.orDefault(family));
    }

    /**
     * Runs an action the player took on a screen.
     * @param connection The connection that sent the request.
     * @param payload The rest of the request.
     */

    private void handleAction(Connection connection, InputBuffer payload) {
        String key = payload.readString();
        int value = payload.readInt();

        // Only a broken client sends an action key this long.
        if (!Utils.isValid(key, Constants.MAX_UI_KEY_LENGTH)) {
            connection.reject("sent an invalid UI action");
            return;
        }

        UiController.handleAction(connection.getSession(), key, value);
    }
}
