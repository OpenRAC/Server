package com.openrac.server.hub;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import com.openrac.net.netty.PacketChannelInitializer;
import com.openrac.net.netty.handlers.PacketHandler;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.IncomingPacket;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.out.AccountPacket;
import com.openrac.net.packets.out.ErrorPacket;
import com.openrac.net.packets.out.HubPacket;
import com.openrac.net.packets.out.RegisterPacket;
import com.openrac.server.Constants;
import com.openrac.server.Session;
import com.openrac.server.SessionRegistry;
import com.openrac.server.net.packets.PacketRouter;
import com.openrac.server.party.PartyController;
import com.openrac.utils.logger.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This server's link to the hub that lists it. It registers on connecting, sends its player and
 * party counts as a heartbeat every few seconds, asks the hub whose session a player's token is,
 * and connects again whenever the hub goes away.
 */

public class HubController {
    // Players waiting on the hub's answer to their token, by request.
    private static final Map<Integer, Session> pending = new ConcurrentHashMap<>();
    private static final AtomicInteger nextRequest = new AtomicInteger(1);

    private static EventLoopGroup group;
    private static String host;
    private static int port;

    private static volatile Channel channel; // Null while there is no link.
    private static volatile boolean refused; // The hub refused this server's key, so it stops trying.
    private static volatile boolean warned; // Whether this outage was logged already.
    private static volatile boolean connecting; // An attempt is under way, so reports wait for it.

    /**
     * Starts connecting, and reports on a schedule from then on.
     * @param loops The event loops the link runs on.
     * @param address The hub's host:port.
     */

    public static void start(EventLoopGroup loops, String address) {
        int colon = address.lastIndexOf(':');

        group = loops;

        // IPv6 addresses come in brackets.
        host = address.substring(0, colon).replace("[", "").replace("]", "");
        port = Integer.parseInt(address.substring(colon + 1));

        connect();

        group.scheduleAtFixedRate(HubController::report, Constants.REGION_REPORT_SECONDS, Constants.REGION_REPORT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Tells the hub this server is closing, so players stop being sent to it.
     */

    public static void stop() {
        Channel link = getLink();

        // Without a link there is nobody to tell.
        if (link == null) return;

        link.writeAndFlush(new HubPacket(Opcodes.Hub.CLOSING)).addListener(ChannelFutureListener.CLOSE).awaitUninterruptibly(2, TimeUnit.SECONDS);
    }

    /**
     * Takes a connected link to the hub and registers on it.
     * @param link The connected channel, its pipeline built by {@link #getInitializer()}.
     */

    public static void attach(Channel link) {
        channel = link;
        warned = false;

        // An unnamed server logs under the process's name.
        String title = Constants.SERVER_NAME.isEmpty() ? Constants.NAME : Constants.SERVER_NAME;

        Log.notice("Registering " + title + " in region " + Constants.REGION + " with the hub at " + link.remoteAddress() + ".");

        link.writeAndFlush(getRegistration());
    }

    /**
     * Asks the hub whose session a player's token is. The player is told once the hub answers.
     * @param session The player that gave the token.
     * @param token The token from its hub login.
     */

    public static void verify(Session session, String token) {
        Channel link = getLink();

        // Without the hub nobody can vouch for the token.
        if (link == null) {
            session.send(new ErrorPacket(Opcodes.Error.TRY_LATER));
            return;
        }

        int request = nextRequest.getAndIncrement();

        pending.put(request, session);
        link.writeAndFlush(new HubPacket(request, token));
    }

    /**
     * Connects to the hub, and registers once connected.
     */

    private static void connect() {
        // Reports that pile up behind a stall would each open a link.
        if (connecting) return;

        connecting = true;

        new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(getInitializer())
                .connect(host, port)
                .addListener((ChannelFutureListener) future -> {
                    connecting = false;

                    // The hub is not up: the next report tries again.
                    if (!future.isSuccess()) {
                        // One line per outage, not one per attempt.
                        if (!warned) Log.warning("Could not reach the hub at " + host + ":" + port + ", retrying.");

                        warned = true;
                        return;
                    }

                    attach(future.channel());
                });
    }

    /**
     * Sends the heartbeat, or connects again.
     */

    private static void report() {
        // A wrong key stays wrong until the operator fixes it.
        if (refused) return;

        Channel link = getLink();

        // No link: try to make one, which registers on success.
        if (link == null) {
            channel = null;
            connect();
            return;
        }

        link.writeAndFlush(getStatus());
    }

    /**
     * Tells the player the hub's answer to its token.
     * @param request The request the hub answers.
     * @param accountId The account the token belongs to, 0 when it is no session.
     * @param name The account's name.
     */

    public static void complete(int request, long accountId, String name) {
        Session session = pending.remove(request);

        // The player left while the hub answered.
        if (session == null) return;

        // Account id 0 means the token is no session.
        if (accountId == 0) {
            session.send(new ErrorPacket(Opcodes.Error.SESSION_INVALID));
            return;
        }

        session.setAccountId(accountId);
        session.send(new AccountPacket(accountId, name, "", false));

        Log.info("Session " + session.getId() + " is account " + accountId + " (" + name + ").");
    }

    /**
     * Acts on a refusal from the hub.
     * @param reason Why the hub refused.
     */

    public static void refuse(Opcodes.Error reason) {
        // A wrong key is final, but a full hub may free up.
        if (reason == Opcodes.Error.REGION_KEY) {
            refused = true;
            Log.error("The hub refused REGION_KEY; this server will not be listed.");
            return;
        }

        Log.warning("The hub refused this server: " + reason + ".");
    }

    /**
     * Answers every waiting player once the link is gone.
     */

    private static void handleClosed() {
        channel = null;

        for (Session session : pending.values()) session.send(new ErrorPacket(Opcodes.Error.TRY_LATER));

        pending.clear();
    }

    /**
     * @return The link to the hub while it is open, else null.
     */

    private static Channel getLink() {
        Channel link = channel;

        // A closed link counts as none.
        return link != null && link.isActive() ? link : null;
    }

    /**
     * @param mode What this process runs.
     * @return The hub to register with: this process's own, or HUB_ADDRESS.
     */

    public static String getAddress(Mode mode) {
        // A single process runs its hub beside it, on this machine.
        return mode == Mode.ALL ? "127.0.0.1:" + com.openrac.hub.Constants.PORT : Constants.HUB_ADDRESS;
    }

    /**
     * @return The pipeline a link to the hub runs: the shared codecs, then the link's handler.
     */

    public static PacketChannelInitializer getInitializer() {
        return new PacketChannelInitializer() {
            /**
             * Ends the pipeline with the hub link's handler.
             * @param pipeline The channel pipeline being built.
             */

            @Override
            protected void addLastHandlers(ChannelPipeline pipeline) {
                pipeline.addLast("hubHandler", new Handler());
            }
        };
    }

    /**
     * @return The registration this server opens its link with.
     */

    public static OutgoingPacket getRegistration() {
        return new RegisterPacket(Constants.REGION_KEY, Constants.REGION.name(), Constants.REGION_HOST, Constants.PORT, SessionRegistry.getCount(),
                Constants.SERVER_NAME);
    }

    /**
     * @return The heartbeat: this server's player and party counts.
     */

    public static OutgoingPacket getStatus() {
        return new HubPacket(SessionRegistry.getCount(), PartyController.getCount());
    }

    /**
     * Reads what the hub sends a server.
     */

    private static class Handler extends PacketHandler {
        /**
         * Forgets the link when it closes.
         * @param ctx The channel handler context.
         */

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            // An old link closing late must not clear the current one.
            if (ctx.channel() == channel) handleClosed();

            ctx.fireChannelInactive();
        }

        /**
         * Routes the hub's answers.
         * @param ctx The channel handler context.
         * @param packet The packet id.
         * @param payload The rest of the packet.
         */

        @Override
        protected void route(ChannelHandlerContext ctx, Packets packet, InputBuffer payload) {
            IncomingPacket<ChannelHandlerContext> incoming = PacketRouter.getHub(packet);

            // Nothing else is meant for a server.
            if (incoming != null) incoming.handle(ctx, payload);
        }
    }
}
