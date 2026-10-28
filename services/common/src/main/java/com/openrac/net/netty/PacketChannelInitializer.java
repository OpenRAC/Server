package com.openrac.net.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import com.openrac.net.NetworkConstants;
import com.openrac.net.codec.PacketDecoder;
import com.openrac.net.codec.PacketEncoder;

/**
 * Builds the pipeline every connection speaks: a 4-byte length prefix around each packet, the packet
 * codecs, then the handlers the server or client adds through {@link #addLastHandlers(ChannelPipeline)}.
 */

public abstract class PacketChannelInitializer extends ChannelInitializer<SocketChannel> {
    /**
     * Initializes the pipeline for a new connection.
     * @param channel The socket channel to initialize.
     */

    @Override
    protected void initChannel(SocketChannel channel) {
        initPipeline(channel.pipeline());
    }

    /**
     * Builds the pipeline on any channel, which lets a test run the real one on an embedded channel.
     * @param pipeline The channel pipeline being built.
     */

    public void initPipeline(ChannelPipeline pipeline) {
        // Inbound: cut frames by their length prefix, then strip it.
        pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(NetworkConstants.MAX_FRAME_LENGTH, 0,
                NetworkConstants.LENGTH_FIELD_LENGTH, 0, NetworkConstants.LENGTH_FIELD_LENGTH));

        // Outbound: put the length ahead of each packet.
        pipeline.addLast("frameEncoder", new LengthFieldPrepender(NetworkConstants.LENGTH_FIELD_LENGTH));

        pipeline.addLast("packetDecoder", new PacketDecoder());
        pipeline.addLast("packetEncoder", new PacketEncoder());

        addLastHandlers(pipeline);
    }

    /**
     * Adds the handlers that terminate the pipeline.
     * @param pipeline The channel pipeline being built.
     */

    protected abstract void addLastHandlers(ChannelPipeline pipeline);
}
