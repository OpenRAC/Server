package com.openrac.server.net.packets.hub.in;

import io.netty.channel.ChannelHandlerContext;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.model.IncomingPacket;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.server.hub.HubController;
import com.openrac.utils.logger.Log;

/**
 * What the hub says on the link: that this server is listed, and whose session a token is.
 */

public class IncomingHubPacket extends IncomingPacket<ChannelHandlerContext> {
    /**
     * Creates the packet; the link is the server's own, so it needs no introduction or limit.
     */

    public IncomingHubPacket() {
        super(false);
    }

    /**
     * Reads the opcode and runs it.
     * @param connection The link to the hub.
     * @param payload The hub packet's payload.
     */

    @Override
    public void handle(ChannelHandlerContext connection, InputBuffer payload) {
        Opcodes.Hub opcode = Opcodes.Hub.get(payload.readUnsignedByte());

        // REGISTERED only confirms, VERIFIED answers a waiting player.
        if (opcode == Opcodes.Hub.REGISTERED) handleRegistered(payload);
        else if (opcode == Opcodes.Hub.VERIFIED) handleVerified(payload);
    }

    /**
     * Logs the id the hub gave this server.
     * @param payload The REGISTERED payload after its opcode.
     */

    private void handleRegistered(InputBuffer payload) {
        Log.notice("Listed by the hub as server " + payload.readInt() + ".");
    }

    /**
     * Passes the hub's answer to the player that waits on it.
     * @param payload The VERIFIED payload after its opcode.
     */

    private void handleVerified(InputBuffer payload) {
        int request = payload.readInt();
        long accountId = payload.readLong();
        String name = payload.readString();

        HubController.complete(request, accountId, name);
    }
}
