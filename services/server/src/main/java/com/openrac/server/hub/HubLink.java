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
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.HelloPacket;
import com.openrac.net.packets.out.RegisterPacket;
import com.openrac.server.Constants;
import com.openrac.server.SessionRegistry;
import com.openrac.utils.logger.Log;

import java.util.concurrent.TimeUnit;

/**
 * A region server's connection to its hub: it says hello, registers, and reports its player count
 * every few seconds, connecting again whenever the hub goes away.
 */

public class HubLink {
    private final EventLoopGroup group;
    private final String host;
    private final int port;

    private volatile Channel channel; // Null while there is no connection.

    /**
     * Creates the link.
     * @param group The event loops it runs on.
     * @param address The hub's host:port.
     */

    public HubLink(EventLoopGroup group, String address) {
        int colon = address.lastIndexOf(':');

        this.group = group;

        // IPv6 addresses come in brackets.
        this.host = address.substring(0, colon).replace("[", "").replace("]", "");
        this.port = Integer.parseInt(address.substring(colon + 1));
    }

    /**
     * Starts connecting, and reports on a schedule from then on.
     */

    public void start() {
        connect();

        group.scheduleAtFixedRate(this::report, Constants.REGION_REPORT_SECONDS, Constants.REGION_REPORT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Connects to the hub, and says hello and registers once connected.
     */

    private void connect() {
        new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new PacketChannelInitializer() {
                    /**
                     * Ends the pipeline with the hub link's handler.
                     * @param pipeline The channel pipeline being built.
                     */

                    @Override
                    protected void addLastHandlers(ChannelPipeline pipeline) {
                        pipeline.addLast("hubHandler", new Handler());
                    }
                })
                .connect(host, port)
                .addListener((ChannelFutureListener) future -> {
                    // The hub is not up yet: the next report tries again.
                    if (!future.isSuccess()) {
                        Log.warning("Could not reach the hub at " + host + ":" + port + ".");
                        return;
                    }

                    channel = future.channel();
                    channel.writeAndFlush(new HelloPacket(Constants.VERSION, "region " + Constants.REGION_NAME));

                    Log.notice("Registering region " + Constants.REGION_NAME + " with the hub at " + host + ":" + port + ".");
                    report();
                });
    }

    /**
     * Sends the registration with the current player count, or connects again.
     */

    private void report() {
        Channel link = channel;

        // No connection: try to make one, which registers on success.
        if (link == null || !link.isActive()) {
            channel = null;
            connect();
            return;
        }

        link.writeAndFlush(new RegisterPacket(Constants.REGION_KEY, Constants.REGION_NAME, Constants.REGION_HOST, Constants.PORT,
                SessionRegistry.getCount()));
    }

    /**
     * Reads what the hub sends a region: nothing it acts on.
     */

    private static class Handler extends PacketHandler {
        /**
         * Ignores the hub's packets (its hello, UDP offer and errors).
         * @param ctx The channel handler context.
         * @param packet The packet id.
         * @param payload The rest of the packet.
         */

        @Override
        protected void route(ChannelHandlerContext ctx, Packets packet, InputBuffer payload) {}
    }
}
