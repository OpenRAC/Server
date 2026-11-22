package com.openrac.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openrac.hub.net.HubChannelInitializer;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.model.OutputBuffer;
import com.openrac.net.packets.out.AccountPacket;
import com.openrac.net.packets.out.HelloPacket;
import com.openrac.net.packets.out.HubPacket;
import com.openrac.net.packets.out.RegionsPacket;
import com.openrac.server.hub.HubController;
import com.openrac.server.hub.Mode;

import java.util.ArrayList;
import java.util.List;

/**
 * A game server's side of the hub: where it registers, what it reports, and players proving their
 * hub login. The hub here runs on its real pipeline in the same process, as "all" mode does.
 */

public class HubLinkTests {
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    /**
     * Closes every channel a test opened.
     */

    @AfterEach
    public void closeChannels() {
        for (EmbeddedChannel channel : channels) channel.close();
    }

    /**
     * @return A connection into this process's hub.
     */

    private EmbeddedChannel connectHub() {
        EmbeddedChannel channel = new EmbeddedChannel();

        new HubChannelInitializer().initPipeline(channel.pipeline());
        channels.add(channel);

        return channel;
    }

    /**
     * Frames a packet and feeds it into a channel.
     * @param channel The receiving end.
     * @param packet The packet.
     */

    private static void send(EmbeddedChannel channel, OutgoingPacket packet) {
        ByteBuf frame = Unpooled.buffer();

        frame.writeInt(packet.size());
        packet.writeTo(frame);

        channel.writeInbound(frame);
    }

    /**
     * Reads the next packet a channel sent and checks its id.
     * @param channel The sending end.
     * @param expected The packet expected next.
     * @return The payload after the id.
     */

    private static InputBuffer receive(EmbeddedChannel channel, Packets expected) {
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

    @Nested
    @DisplayName("Mode Tests")
    class ModeTests {

        @Test
        @DisplayName("A mode reads in any case and an unknown one means region")
        public void testModes() {
            Assertions.assertEquals(Mode.REGION, Mode.get(" Region "));
            Assertions.assertEquals(Mode.ALL, Mode.get("ALL"));
            Assertions.assertEquals(Mode.REGION, Mode.get("nonsense"));
        }

        @Test
        @DisplayName("A region registers with the public hub, all mode with its own")
        public void testAddresses() {
            Assertions.assertEquals("hub.openrac.dev:7177", HubController.getAddress(Mode.REGION));
            Assertions.assertEquals("127.0.0.1:7177", HubController.getAddress(Mode.ALL));
        }
    }

    @Nested
    @DisplayName("Single Process Tests")
    class SingleProcessTests {

        @Test
        @DisplayName("The process's own region is listed by its own hub at its game port")
        public void testOwnRegion() {
            EmbeddedChannel link = connectHub();
            EmbeddedChannel player = connectHub();

            send(link, HubController.getRegistration());
            receive(link, Packets.HUB);
            send(link, HubController.getStatus());

            send(player, new HelloPacket("0.1.0", "Ratchet"));
            receive(player, Packets.HELLO);
            receive(player, Packets.UDP);
            send(player, new RegionsPacket("rac3-pal-1"));

            InputBuffer regions = receive(player, Packets.REGIONS);

            Assertions.assertEquals(1, regions.readUnsignedByte());
            Assertions.assertEquals(Constants.REGION_NAME, regions.readString());
            Assertions.assertEquals("", regions.readString());
            Assertions.assertEquals(Constants.PORT, regions.readInt());
        }

        @Test
        @DisplayName("A game server sends clients asking for regions to the hub")
        public void testNoRegionsHere() {
            Client client = new Client();

            client.hello("Ratchet");
            client.send(new RegionsPacket("rac3-pal-1"));

            Assertions.assertFalse(client.channel.isOpen());
        }
    }

    @Nested
    @DisplayName("Token Tests")
    class TokenTests {

        @Test
        @DisplayName("A token the hub vouches for logs the player in at the server")
        public void testVerified() {
            EmbeddedChannel link = new EmbeddedChannel();

            HubController.getInitializer().initPipeline(link.pipeline());
            channels.add(link);
            HubController.attach(link);
            receive(link, Packets.REGISTER);

            Client client = new Client();

            client.hello("Ratchet");
            client.send(new AccountPacket(Opcodes.Account.TOKEN, "token"));

            InputBuffer verify = receive(link, Packets.HUB);

            Assertions.assertEquals(Opcodes.Hub.VERIFY, Opcodes.Hub.get(verify.readUnsignedByte()));

            OutputBuffer answer = new OutputBuffer();

            answer.writeInt(verify.readInt());
            answer.writeLong(42L);
            answer.writeString("Ratchet");
            send(link, new HubPacket(Opcodes.Hub.VERIFIED, answer));

            InputBuffer loggedIn = client.receive(Packets.ACCOUNT);

            Assertions.assertEquals(Opcodes.Account.LOGGED_IN, Opcodes.Account.get(loggedIn.readUnsignedByte()));
            Assertions.assertEquals(42L, loggedIn.readLong());

            client.channel.close();
        }

        @Test
        @DisplayName("Without a hub link a token is answered with try later")
        public void testNoHub() {
            Client client = new Client();

            client.hello("Ratchet");
            client.send(new AccountPacket(Opcodes.Account.TOKEN, "token"));

            Assertions.assertEquals(Opcodes.Error.TRY_LATER, client.receiveError());

            client.channel.close();
        }
    }
}
