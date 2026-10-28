package com.openrac.net.netty.handlers;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.utils.logger.Log;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Base inbound handler for the packet protocol. It reads the packet id off each decoded frame and
 * hands the payload to {@link #route(ChannelHandlerContext, Packets, InputBuffer)}. An unknown id, a
 * packet shorter than its fields, or any pipeline error closes the connection.
 */

public abstract class PacketHandler extends SimpleChannelInboundHandler<ByteBuffer> {
    /**
     * Resolves the packet id and routes the payload.
     * @param ctx The channel handler context.
     * @param buffer The decoded frame.
     */

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuffer buffer) {
        // An empty frame has no id to route by.
        Packets packet = buffer.hasRemaining() ? Packets.getFromByte(Byte.toUnsignedInt(buffer.get())) : null;

        // A peer sending ids we never defined is not speaking this protocol.
        if (packet == null) {
            reject(ctx, "sent an unknown packet");
            return;
        }

        try {
            route(ctx, packet, new InputBuffer(buffer));
        } catch (BufferUnderflowException e) {
            reject(ctx, "sent a malformed " + packet + " packet");
        }
    }

    /**
     * Closes the connection on any error in the pipeline, an oversized frame included.
     * @param ctx The channel handler context.
     * @param cause The exception that occurred.
     */

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // A peer dropping the link is routine, not a server error.
        if (cause instanceof IOException) Log.info("Connection from " + ctx.channel().remoteAddress() + " lost: " + cause.getMessage() + ".");
        else Log.error("Error on " + ctx.channel().remoteAddress() + ", closing: " + cause.getMessage());

        ctx.close();
    }

    /**
     * Logs why a peer is being dropped and closes its connection.
     * @param ctx The channel handler context.
     * @param reason What the peer did wrong.
     */

    protected void reject(ChannelHandlerContext ctx, String reason) {
        Log.warning(ctx.channel().remoteAddress() + " " + reason + ", closing.");

        ctx.close();
    }

    /**
     * Handles one packet.
     * @param ctx The channel handler context.
     * @param packet The packet id as an enum.
     * @param payload The rest of the packet.
     */

    protected abstract void route(ChannelHandlerContext ctx, Packets packet, InputBuffer payload);
}
