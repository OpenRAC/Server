package com.openrac.server.net;

import io.netty.channel.ChannelPipeline;

import com.openrac.net.netty.PacketChannelInitializer;

/**
 * Ends the shared pipeline with the server's packet handler, one per connection.
 */

public class ServerChannelInitializer extends PacketChannelInitializer {
    /**
     * Adds the server's packet handler.
     * @param pipeline The channel pipeline being built.
     */

    @Override
    protected void addLastHandlers(ChannelPipeline pipeline) {
        pipeline.addLast("packetHandler", new ServerPacketHandler());
    }
}
