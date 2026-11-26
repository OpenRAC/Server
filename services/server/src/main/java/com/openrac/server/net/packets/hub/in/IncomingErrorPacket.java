package com.openrac.server.net.packets.hub.in;

import io.netty.channel.ChannelHandlerContext;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.model.IncomingPacket;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.server.hub.HubController;

/**
 * A refusal from the hub.
 */

public class IncomingErrorPacket extends IncomingPacket<ChannelHandlerContext> {
    /**
     * Creates the packet; the link is the server's own, so it needs no introduction or limit.
     */

    public IncomingErrorPacket() {
        super(false);
    }

    /**
     * Tells the link controller why the hub refused.
     * @param connection The link to the hub.
     * @param payload The error packet's payload.
     */

    @Override
    public void handle(ChannelHandlerContext connection, InputBuffer payload) {
        HubController.refuse(Opcodes.Error.get(payload.readUnsignedByte()));
    }
}
