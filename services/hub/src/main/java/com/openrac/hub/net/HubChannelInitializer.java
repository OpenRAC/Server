package com.openrac.hub.net;

import io.netty.channel.ChannelPipeline;

import com.openrac.net.netty.PacketChannelInitializer;

/**
 * Ends the shared pipeline with the hub's packet handler, one per connection.
 */

public class HubChannelInitializer extends PacketChannelInitializer {
    /**
     * Adds the hub's packet handler.
     * @param pipeline The channel pipeline being built.
     */

    @Override
    protected void addLastHandlers(ChannelPipeline pipeline) {
        pipeline.addLast("hubHandler", new HubPacketHandler());
    }
}
