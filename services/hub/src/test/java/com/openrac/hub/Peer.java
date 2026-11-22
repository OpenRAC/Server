package com.openrac.hub;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;

import com.openrac.hub.net.HubChannelInitializer;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.out.HelloPacket;
import com.openrac.net.packets.out.RegisterPacket;

/**
 * One connection to the hub's pipeline, run on an embedded channel: a player's client or a game
 * server.
 */

public class Peer {
    public final EmbeddedChannel channel = new EmbeddedChannel();

    /**
     * Builds the hub's pipeline on the embedded channel.
     */

    public Peer() {
        new HubChannelInitializer().initPipeline(channel.pipeline());
    }

    /**
     * Says hello as a client and reads the greeting.
     * @param name The display name.
     * @return The feature bits the hub said it takes.
     */

    public int hello(String name) {
        send(new HelloPacket("0.1.0", name));
        receive(Packets.HELLO).readInt();

        InputBuffer udp = receive(Packets.UDP);

        udp.readLong();
        udp.readInt();

        return udp.readUnsignedByte();
    }

    /**
     * Registers as a game server and reads its id.
     * @param region The region it is listed under.
     * @param port Its game port.
     * @return The id the hub gave it.
     */

    public int register(String region, int port) {
        send(new RegisterPacket("", region, "", port, 0, region + " box"));

        InputBuffer payload = receiveHub(Opcodes.Hub.REGISTERED);

        return payload.readInt();
    }

    /**
     * Frames a packet the way a peer would and feeds it to the hub.
     * @param packet The packet to send.
     */

    public void send(OutgoingPacket packet) {
        ByteBuf frame = Unpooled.buffer();

        frame.writeInt(packet.size());
        packet.writeTo(frame);

        channel.writeInbound(frame);
    }

    /**
     * Reads the next packet the hub sent and checks its id.
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

        length.release();
        body.release();

        InputBuffer payload = new InputBuffer(bytes);

        Assertions.assertEquals(expected, Packets.getFromByte(payload.readUnsignedByte()));

        return payload;
    }

    /**
     * @param expected The hub opcode expected next.
     * @return The payload after the opcode.
     */

    public InputBuffer receiveHub(Opcodes.Hub expected) {
        InputBuffer payload = receive(Packets.HUB);

        Assertions.assertEquals(expected, Opcodes.Hub.get(payload.readUnsignedByte()));

        return payload;
    }

    /**
     * @param expected The servers opcode expected next.
     * @return The payload after the opcode.
     */

    public InputBuffer receiveServers(Opcodes.Servers expected) {
        InputBuffer payload = receive(Packets.SERVERS);

        Assertions.assertEquals(expected, Opcodes.Servers.get(payload.readUnsignedByte()));

        return payload;
    }

    /**
     * @return The reason of the next packet, which must be an error.
     */

    public Opcodes.Error receiveError() {
        return Opcodes.Error.get(receive(Packets.ERROR).readUnsignedByte());
    }

    /**
     * Checks the hub sent this peer nothing more.
     */

    public void assertSilent() {
        Assertions.assertNull(channel.readOutbound());
    }
}
