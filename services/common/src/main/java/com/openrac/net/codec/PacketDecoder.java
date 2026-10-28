package com.openrac.net.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Turns each frame cut by the {@code LengthFieldBasedFrameDecoder} into a heap {@link ByteBuffer}
 * for the packet handler, so no pooled Netty buffer outlives the read.
 */

public class PacketDecoder extends ByteToMessageDecoder {
    /**
     * Copies a frame out of its Netty buffer.
     * @param ctx The channel handler context.
     * @param in The frame, without its length prefix.
     * @param out The list to add decoded messages to.
     */

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        byte[] bytes = new byte[in.readableBytes()];

        in.readBytes(bytes);

        out.add(ByteBuffer.wrap(bytes));
    }
}
