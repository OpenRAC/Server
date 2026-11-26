package com.openrac.hub.net.packets.in;

import com.openrac.hub.Constants;
import com.openrac.hub.accounts.AccountController;
import com.openrac.hub.net.Connection;
import com.openrac.hub.net.packets.model.IncomingPacket;
import com.openrac.hub.servers.ServerController;
import com.openrac.hub.stats.StatsController;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.model.GameResult;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.utils.Clock;
import com.openrac.utils.Utils;

/**
 * Runs a registered server's heartbeat, its leaving, its session checks and its game results.
 */

public class IncomingHubPacket extends IncomingPacket {
    /**
     * Creates the packet; only a connection that is known may use the hub link.
     */

    public IncomingHubPacket() {
        super(true);
    }

    /**
     * Reads the opcode and runs it.
     * @param connection The connection that sent the packet.
     * @param payload The hub packet's payload.
     */

    @Override
    public void handle(Connection connection, InputBuffer payload) {
        // Only a registered server speaks on the hub link.
        if (connection.getServer() == null) {
            connection.reject("sent HUB without registering");
            return;
        }

        switch (Opcodes.Hub.get(payload.readUnsignedByte())) {
            case STATUS -> ServerController.status(connection.getServer(), payload.readInt(), payload.readInt(), Clock.now());
            case CLOSING -> ServerController.remove(connection.getServer());
            case VERIFY -> handleVerify(connection, payload);
            case RESULT -> handleResult(connection, payload);

            // The rest only the hub sends.
            case null, default -> connection.reject("sent an invalid hub opcode");
        }
    }

    /**
     * Checks a player's session token for a game server.
     * @param connection The connection of the server.
     * @param payload The VERIFY payload after its opcode.
     */

    private void handleVerify(Connection connection, InputBuffer payload) {
        int request = payload.readInt();
        String token = connection.readToken(payload);

        // An invalid token already closed the connection.
        if (token != null) AccountController.verify(connection.getChannel(), request, token);
    }

    /**
     * Stores a game a server reports as finished.
     * @param connection The connection of the server.
     * @param payload The RESULT payload after its opcode.
     */

    private void handleResult(Connection connection, InputBuffer payload) {
        GameResult result = GameResult.read(payload, Constants.MAX_RESULT_PLAYERS);

        // An oversized scoreboard or bad family means a broken server.
        if (result == null || !Utils.isWord(result.family(), Constants.MAX_FAMILY_LENGTH) || result.name().length() > Constants.MAX_NAME_LENGTH) {
            connection.reject("sent an invalid game result");
            return;
        }

        StatsController.record(connection.getServer(), connection.getChannel(), result);
    }
}
