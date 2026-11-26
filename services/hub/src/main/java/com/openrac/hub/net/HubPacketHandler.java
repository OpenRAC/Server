package com.openrac.hub.net;

import io.netty.channel.ChannelHandlerContext;

import com.openrac.hub.Constants;
import com.openrac.hub.net.packets.PacketRouter;
import com.openrac.hub.net.packets.model.IncomingPacket;
import com.openrac.hub.servers.ServerController;
import com.openrac.net.limits.ConnectionLimit;
import com.openrac.net.limits.RateLimit;
import com.openrac.net.netty.handlers.PacketHandler;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.ErrorPacket;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Handles one connection to the hub: it admits it, finds the class that takes each packet in the
 * {@link PacketRouter}, enforces what that packet requires, and cleans up when the connection
 * closes. Each connection gets its own instance, so its state is only touched on its event loop.
 */

public class HubPacketHandler extends PacketHandler {
    private Connection connection;

    /**
     * Wraps the connection as soon as the handler joins its pipeline, which can be after it is active.
     * @param ctx The channel handler context.
     */

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        connection = new Connection(ctx);
    }

    /**
     * Admits a new connection within its address's limit and gives it a deadline to introduce itself.
     * @param ctx The channel handler context.
     */

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Only network connections have an address to count against.
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress remote) {
            // One machine cannot exhaust the hub with sockets.
            if (!ConnectionLimit.open(remote.getAddress(), Constants.MAX_CONNECTIONS_PER_ADDRESS)) {
                reject(ctx, "has too many connections open");
                return;
            }

            connection.setAddress(remote.getAddress());
        }

        // A connection that never introduces itself would hold a socket for nothing.
        ctx.executor().schedule(() -> {
            // Still a stranger and still open when the deadline comes.
            if (!connection.isKnown() && ctx.channel().isActive()) reject(ctx, "did not say hello or register in time");
        }, Constants.HELLO_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        ctx.fireChannelActive();
    }

    /**
     * Takes a closed server's connection off the list.
     * @param ctx The channel handler context.
     */

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // A refused connection never counted against its address.
        if (connection.getAddress() != null) ConnectionLimit.close(connection.getAddress());

        // A client's watch ends with its channel, so only servers leave.
        if (connection.getServer() != null) ServerController.remove(connection.getServer());

        ctx.fireChannelInactive();
    }

    /**
     * Looks up the packet, checks what it requires and hands it its payload.
     * @param ctx The channel handler context.
     * @param packet The packet id as an enum.
     * @param payload The rest of the packet.
     */

    @Override
    protected void route(ChannelHandlerContext ctx, Packets packet, InputBuffer payload) {
        IncomingPacket incoming = PacketRouter.get(packet);

        // A connection says what it is before anything else.
        if (!connection.isKnown() && (incoming == null || incoming.isStrict())) {
            reject(ctx, "sent " + packet + " before hello or register");
            return;
        }

        // Lobbies, parties and game data live on the game servers.
        if (incoming == null) {
            reject(ctx, "sent " + packet + ", which the hub does not take");
            return;
        }

        RateLimit limit = incoming.getRateLimit(connection);

        // Over its rate, a request is refused for now.
        if (limit != null && !limit.take()) {
            ctx.writeAndFlush(new ErrorPacket(Opcodes.Error.TRY_LATER));
            return;
        }

        incoming.handle(connection, payload);
    }
}
