package com.openrac.server.http;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import com.openrac.server.Constants;

/**
 * The HTTP side-port's pipeline: a whole request, within the size limit, to the handler.
 */

public class HttpChannelInitializer extends ChannelInitializer<SocketChannel> {
    private final ModSetStore store;
    private final HttpRateLimits limits = new HttpRateLimits();

    /**
     * @param store The sets every connection shares.
     */

    public HttpChannelInitializer(ModSetStore store) {
        this.store = store;
    }

    /**
     * Builds one connection's pipeline.
     * @param channel The new connection.
     */

    @Override
    protected void initChannel(SocketChannel channel) {
        initPipeline(channel.pipeline());
    }

    /**
     * Builds the pipeline on any channel, which lets a test run the real one.
     * @param pipeline The channel pipeline being built.
     */

    public void initPipeline(ChannelPipeline pipeline) {
        pipeline.addLast("http", new HttpServerCodec())
                .addLast("aggregator", new HttpObjectAggregator(Constants.MODSET_MAX_BODY_BYTES))
                .addLast("modSets", new ModSetHandler(store, limits));
    }
}
