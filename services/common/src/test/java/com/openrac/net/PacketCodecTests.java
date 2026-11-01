package com.openrac.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openrac.net.datagrams.Datagrams;
import com.openrac.net.datagrams.OutgoingDatagram;
import com.openrac.net.netty.PacketChannelInitializer;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.model.OutputBuffer;
import com.openrac.net.packets.out.ChatPacket;
import com.openrac.net.packets.out.DataPacket;
import com.openrac.net.packets.out.ErrorPacket;
import com.openrac.net.packets.out.HelloPacket;
import com.openrac.net.packets.out.PartyPacket;
import com.openrac.net.packets.out.TimePacket;
import com.openrac.net.packets.out.UdpPacket;

import java.nio.ByteBuffer;

/**
 * Sends every packet through the real pipeline's encoders and back through its decoders.
 */

public class PacketCodecTests {

    /**
     * Encodes a packet into a frame, decodes the frame and checks the packet id.
     * @param packet The packet to send through.
     * @param expected The packet id the frame should start with.
     * @return The payload after the packet id.
     */

    private static InputBuffer roundTrip(OutgoingPacket packet, Packets expected) {
        EmbeddedChannel channel = createChannel();

        Assertions.assertTrue(channel.writeOutbound(packet));

        // The prepender sends the length and the packet as two buffers.
        ByteBuf length = channel.readOutbound();
        ByteBuf body = channel.readOutbound();

        Assertions.assertEquals(packet.size(), length.getInt(0));
        Assertions.assertEquals(packet.size(), body.readableBytes());
        Assertions.assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(length, body)));

        ByteBuffer buffer = channel.readInbound();
        InputBuffer payload = new InputBuffer(buffer);

        Assertions.assertEquals(expected, Packets.getFromByte(payload.readUnsignedByte()));

        return payload;
    }

    /**
     * @return A channel with the shared pipeline and nothing after it.
     */

    private static EmbeddedChannel createChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();

        new PacketChannelInitializer() {
            @Override
            protected void addLastHandlers(ChannelPipeline pipeline) {}
        }.initPipeline(channel.pipeline());

        return channel;
    }

    @Nested
    @DisplayName("Hello Tests")
    class HelloTests {

        @Test
        @DisplayName("Client hello carries the version and name")
        public void testClientHello() {
            InputBuffer payload = roundTrip(new HelloPacket("0.1.0", "Ratchet"), Packets.HELLO);

            Assertions.assertEquals("0.1.0", payload.readString());
            Assertions.assertEquals("Ratchet", payload.readString());
            Assertions.assertEquals(0, payload.size());
        }

        @Test
        @DisplayName("Server hello carries the session id")
        public void testServerHello() {
            InputBuffer payload = roundTrip(new HelloPacket(42), Packets.HELLO);

            Assertions.assertEquals(42, payload.readInt());
            Assertions.assertEquals(0, payload.size());
        }
    }

    @Nested
    @DisplayName("Party Tests")
    class PartyTests {

        @Test
        @DisplayName("Opcodes without a payload carry only the opcode")
        public void testBareOpcodes() {
            for (Opcodes.Party opcode : new Opcodes.Party[] { Opcodes.Party.CREATE, Opcodes.Party.LEAVE, Opcodes.Party.END, Opcodes.Party.FIND, Opcodes.Party.LIST }) {
                InputBuffer payload = roundTrip(new PartyPacket(opcode), Packets.PARTY);

                Assertions.assertEquals(opcode, Opcodes.Party.get(payload.readUnsignedByte()));
                Assertions.assertEquals(0, payload.size());
            }
        }

        @Test
        @DisplayName("Join carries the code")
        public void testJoin() {
            InputBuffer payload = roundTrip(new PartyPacket(Opcodes.Party.JOIN, "ABC234"), Packets.PARTY);

            Assertions.assertEquals(Opcodes.Party.JOIN, Opcodes.Party.get(payload.readUnsignedByte()));
            Assertions.assertEquals("ABC234", payload.readString());
            Assertions.assertEquals(0, payload.size());
        }

        @Test
        @DisplayName("Create carries the description")
        public void testCreateDescription() {
            InputBuffer payload = roundTrip(new PartyPacket(Opcodes.Party.CREATE, "Chill run"), Packets.PARTY);

            Assertions.assertEquals(Opcodes.Party.CREATE, Opcodes.Party.get(payload.readUnsignedByte()));
            Assertions.assertEquals("Chill run", payload.readString());
            Assertions.assertEquals(0, payload.size());
        }

        @Test
        @DisplayName("An empty description is written as the empty string marker")
        public void testEmptyDescription() {
            InputBuffer payload = roundTrip(new PartyPacket(Opcodes.Party.CREATE, ""), Packets.PARTY);

            payload.readUnsignedByte();
            Assertions.assertEquals(-1, payload.buffer().getShort(payload.buffer().position()));
            Assertions.assertEquals("", payload.readString());
            Assertions.assertEquals(0, payload.size());
        }

        @Test
        @DisplayName("Left carries the session id")
        public void testLeft() {
            InputBuffer payload = roundTrip(new PartyPacket(Opcodes.Party.LEFT, 7), Packets.PARTY);

            Assertions.assertEquals(Opcodes.Party.LEFT, Opcodes.Party.get(payload.readUnsignedByte()));
            Assertions.assertEquals(7, payload.readInt());
            Assertions.assertEquals(0, payload.size());
        }

        @Test
        @DisplayName("Info and joined carry their serialized data unchanged")
        public void testSerializedData() {
            for (Opcodes.Party opcode : new Opcodes.Party[] { Opcodes.Party.INFO, Opcodes.Party.JOINED }) {
                OutputBuffer data = new OutputBuffer();

                data.writeInt(3);
                data.writeString("Clank");

                InputBuffer payload = roundTrip(new PartyPacket(opcode, data), Packets.PARTY);

                Assertions.assertEquals(opcode, Opcodes.Party.get(payload.readUnsignedByte()));
                Assertions.assertEquals(3, payload.readInt());
                Assertions.assertEquals("Clank", payload.readString());
                Assertions.assertEquals(0, payload.size());
            }
        }
    }

    @Nested
    @DisplayName("Chat Tests")
    class ChatTests {

        @Test
        @DisplayName("Client chat carries the message")
        public void testClientChat() {
            InputBuffer payload = roundTrip(new ChatPacket("Héllo, Veldin"), Packets.CHAT);

            Assertions.assertEquals("Héllo, Veldin", payload.readString());
            Assertions.assertEquals(0, payload.size());
        }

        @Test
        @DisplayName("Server chat carries the sender and message")
        public void testServerChat() {
            InputBuffer payload = roundTrip(new ChatPacket(5, "Hi"), Packets.CHAT);

            Assertions.assertEquals(5, payload.readInt());
            Assertions.assertEquals("Hi", payload.readString());
            Assertions.assertEquals(0, payload.size());
        }
    }

    @Nested
    @DisplayName("Data Tests")
    class DataTests {

        @Test
        @DisplayName("Data carries the session id and the payload byte for byte")
        public void testData() {
            byte[] bytes = { 0, 1, -1, 127, -128 };
            InputBuffer payload = roundTrip(new DataPacket(NetworkConstants.BROADCAST, bytes), Packets.DATA);

            Assertions.assertEquals(NetworkConstants.BROADCAST, payload.readInt());
            Assertions.assertArrayEquals(bytes, payload.readRemainingBytes());
        }

        @Test
        @DisplayName("Data with an empty payload carries only the session id")
        public void testEmptyData() {
            InputBuffer payload = roundTrip(new DataPacket(9, new byte[0]), Packets.DATA);

            Assertions.assertEquals(9, payload.readInt());
            Assertions.assertEquals(0, payload.size());
        }
    }

    @Nested
    @DisplayName("Time Tests")
    class TimeTests {

        @Test
        @DisplayName("A clock request carries the client's time")
        public void testClientTime() {
            InputBuffer payload = roundTrip(new TimePacket(Long.MAX_VALUE - 7), Packets.TIME);

            Assertions.assertEquals(Long.MAX_VALUE - 7, payload.readLong());
            Assertions.assertEquals(0, payload.size());
        }

        @Test
        @DisplayName("A clock answer carries the echoed time and the server's")
        public void testServerTime() {
            InputBuffer payload = roundTrip(new TimePacket(123_456_789_012L, 42L), Packets.TIME);

            Assertions.assertEquals(123_456_789_012L, payload.readLong());
            Assertions.assertEquals(42L, payload.readLong());
            Assertions.assertEquals(0, payload.size());
        }
    }

    @Nested
    @DisplayName("Udp Tests")
    class UdpTests {

        @Test
        @DisplayName("A UDP offer carries the token, the port, the feature bits and the resume token")
        public void testUdpOffer() {
            InputBuffer payload = roundTrip(new UdpPacket(-0x1234_5678_9ABC_DEF0L, 7770, 1, 42L), Packets.UDP);

            Assertions.assertEquals(-0x1234_5678_9ABC_DEF0L, payload.readLong());
            Assertions.assertEquals(7770, payload.readInt());
            Assertions.assertEquals(1, payload.readUnsignedByte());
            Assertions.assertEquals(42L, payload.readLong());
            Assertions.assertEquals(0, payload.size());
        }
    }

    @Nested
    @DisplayName("Datagram Tests")
    class DatagramTests {

        @Test
        @DisplayName("A bind carries the session id, the token and the kind only")
        public void testBind() {
            InputBuffer payload = new InputBuffer(new OutgoingDatagram(3, Long.MIN_VALUE, Datagrams.BIND).toByteArray());

            Assertions.assertEquals(3, payload.readInt());
            Assertions.assertEquals(Long.MIN_VALUE, payload.readLong());
            Assertions.assertEquals(Datagrams.BIND, Datagrams.get(payload.readUnsignedByte()));
            Assertions.assertEquals(0, payload.size());
        }

        @Test
        @DisplayName("Client state carries the header, the target and the bytes")
        public void testClientState() {
            byte[] bytes = { 0, 1, -1, 127, -128 };
            InputBuffer payload = new InputBuffer(new OutgoingDatagram(3, 99L, NetworkConstants.BROADCAST, bytes).toByteArray());

            Assertions.assertEquals(3, payload.readInt());
            Assertions.assertEquals(99L, payload.readLong());
            Assertions.assertEquals(Datagrams.STATE, Datagrams.get(payload.readUnsignedByte()));
            Assertions.assertEquals(NetworkConstants.BROADCAST, payload.readInt());
            Assertions.assertArrayEquals(bytes, payload.readRemainingBytes());
        }

        @Test
        @DisplayName("Server state carries the kind, the sender and the bytes")
        public void testServerState() {
            byte[] bytes = { 4, 5 };
            InputBuffer payload = new InputBuffer(new OutgoingDatagram(8, bytes).toByteArray());

            Assertions.assertEquals(Datagrams.STATE, Datagrams.get(payload.readUnsignedByte()));
            Assertions.assertEquals(8, payload.readInt());
            Assertions.assertArrayEquals(bytes, payload.readRemainingBytes());
        }

        @Test
        @DisplayName("Kinds past the last resolve to nothing")
        public void testUnknownKind() {
            Assertions.assertNull(Datagrams.get(Datagrams.values().length));
            Assertions.assertNull(Datagrams.get(-1));
        }
    }

    @Nested
    @DisplayName("Error Tests")
    class ErrorTests {

        @Test
        @DisplayName("Every error reason survives the round trip")
        public void testErrors() {
            for (Opcodes.Error reason : Opcodes.Error.values()) {
                InputBuffer payload = roundTrip(new ErrorPacket(reason), Packets.ERROR);

                Assertions.assertEquals(reason, Opcodes.Error.get(payload.readUnsignedByte()));
                Assertions.assertEquals(0, payload.size());
            }
        }
    }

    @Nested
    @DisplayName("Framing Tests")
    class FramingTests {

        @Test
        @DisplayName("A frame split across reads decodes once complete")
        public void testSplitFrame() {
            EmbeddedChannel channel = createChannel();
            byte[] packet = new HelloPacket(1).toByteArray();

            ByteBuf frame = Unpooled.buffer();

            frame.writeInt(packet.length);
            frame.writeBytes(packet);

            Assertions.assertFalse(channel.writeInbound(frame.readRetainedSlice(3)));
            Assertions.assertTrue(channel.writeInbound(frame.readRetainedSlice(frame.readableBytes())));

            ByteBuffer buffer = channel.readInbound();

            Assertions.assertArrayEquals(packet, buffer.array());

            frame.release();
        }

        @Test
        @DisplayName("Ids past the last packet resolve to nothing")
        public void testUnknownId() {
            Assertions.assertNull(Packets.getFromByte(Packets.values().length));
            Assertions.assertNull(Opcodes.Party.get(255));
            Assertions.assertNull(Opcodes.Error.get(-1));
        }
    }
}
