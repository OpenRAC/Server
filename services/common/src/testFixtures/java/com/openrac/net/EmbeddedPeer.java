package com.openrac.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;

import com.openrac.net.netty.PacketChannelInitializer;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.model.OutgoingPacket;

/**
 * One TCP connection into a service's real pipeline, run on an embedded channel: what the tests of
 * every service send packets through and read the answers from.
 */

public class EmbeddedPeer {
    public final EmbeddedChannel channel = new EmbeddedChannel();

    /**
     * Builds a pipeline on the embedded channel.
     * @param initializer The service's pipeline.
     */

    public EmbeddedPeer(PacketChannelInitializer initializer) {
        initializer.initPipeline(channel.pipeline());
    }

    /**
     * Frames a packet the way a peer would and feeds it in.
     * @param packet The packet to send.
     */

    public void send(OutgoingPacket packet) {
        ByteBuf frame = Unpooled.buffer();

        frame.writeInt(packet.size());
        packet.writeTo(frame);

        channel.writeInbound(frame);
    }

    /**
     * Reads the next packet the service sent and checks its id.
     * @param expected The packet expected next.
     * @return The payload after the id.
     */

    public InputBuffer receive(Packets expected) {
        // The prepender sends the length and the packet as two buffers.
        ByteBuf length = channel.readOutbound();

        Assertions.assertNotNull(length, "Expected " + expected + " but nothing was sent.");

        ByteBuf body = channel.readOutbound();
        byte[] bytes = new byte[length.readInt()];

        body.readBytes(bytes);

        Assertions.assertEquals(0, body.readableBytes());

        length.release();
        body.release();

        InputBuffer payload = new InputBuffer(bytes);

        Assertions.assertEquals(expected, Packets.getFromByte(payload.readUnsignedByte()));

        return payload;
    }

    /**
     * @return The reason of the next packet, which must be an error.
     */

    public Opcodes.Error receiveError() {
        return Opcodes.Error.get(receive(Packets.ERROR).readUnsignedByte());
    }

    /**
     * Checks the service sent nothing more.
     */

    public void assertSilent() {
        Assertions.assertNull(channel.readOutbound());
    }
}
