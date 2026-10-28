package com.openrac.net.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import com.openrac.net.packets.model.OutgoingPacket;

/**
 * Writes an outgoing packet's bytes into a Netty buffer. It sits above the
 * {@code LengthFieldPrepender}, which puts the length ahead of them.
 */

public class PacketEncoder extends MessageToByteEncoder<OutgoingPacket> {
    /**
     * Encodes a packet.
     * @param ctx The channel handler context.
     * @param packet The packet to write.
     * @param out The buffer the packet's bytes go into.
     */

    @Override
    protected void encode(ChannelHandlerContext ctx, OutgoingPacket packet, ByteBuf out) {
        packet.writeTo(out);
    }
}
