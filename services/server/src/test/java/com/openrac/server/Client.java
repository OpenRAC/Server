package com.openrac.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.out.HelloPacket;
import com.openrac.net.packets.out.PartyPacket;
import com.openrac.server.net.ServerChannelInitializer;

import java.util.Locale;

/**
 * One player's TCP connection to the server's pipeline, run on an embedded channel.
 */

class Client {
    final EmbeddedChannel channel = new EmbeddedChannel();

    int id;

    /**
     * Builds the server's pipeline on the embedded channel.
     */

    Client() {
        new ServerChannelInitializer().initPipeline(channel.pipeline());
    }

    /**
     * Says hello, then reads the session id.
     * @param name The display name.
     */

    void hello(String name) {
        send(new HelloPacket("0.1.0", name));
        id = receive(Packets.HELLO).readInt();
    }

    /**
     * Creates a party.
     * @return The party's join code.
     */

    String create() {
        send(new PartyPacket(Opcodes.Party.CREATE));

        InputBuffer info = receiveParty(Opcodes.Party.INFO);
        String code = info.readString();

        Assertions.assertEquals(id, info.readInt());
        Assertions.assertEquals(1, info.readUnsignedByte());

        return code;
    }

    /**
     * Joins a party, and checks the member count it is told.
     * @param code The join code.
     * @param members How many members the party has once it joined.
     */

    void join(String code, int members) {
        send(new PartyPacket(Opcodes.Party.JOIN, code));

        InputBuffer info = receiveParty(Opcodes.Party.INFO);

        Assertions.assertEquals(code.toUpperCase(Locale.ROOT), info.readString());
        info.readInt();
        Assertions.assertEquals(members, info.readUnsignedByte());
    }

    /**
     * Frames a packet the way a game client would and feeds it to the server.
     * @param packet The packet to send.
     */

    void send(OutgoingPacket packet) {
        ByteBuf frame = Unpooled.buffer();

        frame.writeInt(packet.size());
        packet.writeTo(frame);

        channel.writeInbound(frame);
    }

    /**
     * Reads the next packet the server sent and checks its id.
     * @param expected The packet expected next.
     * @return The payload after the id.
     */

    InputBuffer receive(Packets expected) {
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
     * Reads the next packet, expecting a party notice with the opcode.
     * @param expected The party opcode expected next.
     * @return The payload after the opcode.
     */

    InputBuffer receiveParty(Opcodes.Party expected) {
        InputBuffer payload = receive(Packets.PARTY);

        Assertions.assertEquals(expected, Opcodes.Party.get(payload.readUnsignedByte()));

        return payload;
    }

    /**
     * @return The reason of the next packet, which must be an error.
     */

    Opcodes.Error receiveError() {
        return Opcodes.Error.get(receive(Packets.ERROR).readUnsignedByte());
    }

    /**
     * Checks the server sent this client nothing more.
     */

    void assertSilent() {
        Assertions.assertNull(channel.readOutbound());
    }
}
