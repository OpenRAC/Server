package com.openrac.server.net;

import io.netty.channel.ChannelHandlerContext;

import com.openrac.net.limits.ConnectionLimit;
import com.openrac.net.limits.RateLimit;
import com.openrac.net.netty.handlers.PacketHandler;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.server.Constants;
import com.openrac.server.Session;
import com.openrac.server.SessionRegistry;
import com.openrac.server.net.packets.PacketRouter;
import com.openrac.server.net.packets.model.IncomingPacket;
import com.openrac.server.party.PartyController;
import com.openrac.utils.Clock;
import com.openrac.utils.logger.Log;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Handles one player's connection: it admits it, finds the class that takes each packet in the
 * {@link PacketRouter}, enforces what that packet requires, and cleans up when the connection
 * drops. Each connection gets its own instance, so its state is only touched on its event loop.
 */

public class GamePacketHandler extends PacketHandler {
    private Connection connection;
    private boolean warned; // Whether this connection's first dropped packet was logged.

    /**
     * Wraps the connection as soon as the handler joins its pipeline, which can be after it is active.
     * @param ctx The channel handler context.
     */

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        connection = new Connection(ctx);
    }

    /**
     * Admits a new connection within its address's limit and gives it a deadline to say hello.
     * @param ctx The channel handler context.
     */

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Only network connections have an address to count against.
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress remote) {
            if (!ConnectionLimit.open(remote.getAddress(), Constants.MAX_CONNECTIONS_PER_ADDRESS)) {
                reject(ctx, "has too many connections open");
                return;
            }

            connection.setAddress(remote.getAddress());
        }

        Log.info("Connection from " + ctx.channel().remoteAddress() + ".");

        // A connection that never says hello would hold a socket for nothing.
        ctx.executor().schedule(() -> {
            if (connection.getSession() == null && ctx.channel().isActive()) reject(ctx, "did not say hello in time");
        }, Constants.HELLO_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        ctx.fireChannelActive();
    }

    /**
     * Takes a dropped connection out of its party.
     * @param ctx The channel handler context.
     */

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Session session = connection.getSession();

        // A refused connection never counted against its address.
        if (connection.getAddress() != null) ConnectionLimit.close(connection.getAddress());

        // A session that moved to a newer connection has not gone anywhere.
        if (session != null && session.isOn(ctx.channel())) {
            // Datagrams naming a closed session are dropped until it comes back.
            SessionRegistry.remove(session);

            PartyController.dropped(session, Clock.now());
        }

        // Without a hello there is only the address to name.
        String who = session == null ? "Connection from " + ctx.channel().remoteAddress() : "Session " + session.getId() + " (" + session.getName() + ")";

        Log.info(who + " disconnected.");

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
        IncomingPacket incoming = PacketRouter.getGame(packet);

        // Server-only packets and the hub's have no business on a player's connection.
        if (incoming == null) {
            reject(ctx, "sent " + packet + ", which a game server does not take from players");
            return;
        }

        // Everything but the greeting needs a session first.
        if (incoming.isStrict() && connection.getSession() == null) {
            reject(ctx, "sent " + packet + " before hello");
            return;
        }

        if (connection.getSession() != null) connection.getSession().touch();

        RateLimit limit = incoming.getRateLimit(connection);

        // Over its rate, a packet is dropped and counts as a strike.
        if (limit != null && !limit.take()) {
            strike(ctx, packet);
            return;
        }

        incoming.handle(connection, payload);
    }

    /**
     * Counts a dropped packet, closing a connection that keeps flooding.
     * @param ctx The channel handler context.
     * @param packet The packet that was over its rate.
     */

    private void strike(ChannelHandlerContext ctx, Packets packet) {
        // Strikes are forgiven over time, so only a sustained flood is closed.
        if (!connection.getStrikeLimit().take()) {
            reject(ctx, "kept flooding " + packet + " packets");
            return;
        }

        // One line per connection, not one per dropped packet.
        if (warned) return;

        warned = true;
        Log.warning("Session " + connection.getSession().getId() + " (" + connection.getSession().getName() + ") is over its " + packet + " rate; dropping packets.");
    }
}
