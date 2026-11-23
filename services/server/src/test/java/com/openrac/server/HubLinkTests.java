package com.openrac.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openrac.hub.net.HubChannelInitializer;
import com.openrac.net.netty.PacketChannelInitializer;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.model.OutputBuffer;
import com.openrac.net.packets.out.AccountPacket;
import com.openrac.net.packets.out.HelloPacket;
import com.openrac.net.packets.out.HubPacket;
import com.openrac.net.packets.out.RegionsPacket;
import com.openrac.server.hub.HubController;
import com.openrac.server.hub.Mode;
import com.openrac.server.net.ServerChannelInitializer;

import java.util.ArrayList;
import java.util.List;

/**
 * A game server's side of the hub: where it registers, what it reports, and players proving their
 * hub login. The hub here runs on its real pipeline in the same process, as "all" mode does.
 */

public class HubLinkTests {
    private final List<Client> clients = new ArrayList<>();

    /**
     * Closes every connection a test opened.
     */

    @AfterEach
    public void closeClients() {
        for (Client client : clients) client.channel.close();
    }

    /**
     * @param initializer The pipeline the connection runs.
     * @return A new connection on it.
     */

    private Client connect(PacketChannelInitializer initializer) {
        Client client = new Client(initializer);

        clients.add(client);

        return client;
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
            Client link = connect(new HubChannelInitializer());
            Client player = connect(new HubChannelInitializer());

            link.send(HubController.getRegistration());
            link.receive(Packets.HUB);
            link.send(HubController.getStatus());

            player.send(new HelloPacket("0.1.0", "Ratchet"));
            player.receive(Packets.HELLO);
            player.receive(Packets.UDP);
            player.send(new RegionsPacket("rac3-pal-1"));

            InputBuffer regions = player.receive(Packets.REGIONS);

            Assertions.assertEquals(1, regions.readUnsignedByte());
            Assertions.assertEquals(Constants.REGION.name(), regions.readString());
            Assertions.assertEquals("", regions.readString());
            Assertions.assertEquals(Constants.PORT, regions.readInt());
        }

        @Test
        @DisplayName("A game server sends clients asking for regions to the hub")
        public void testNoRegionsHere() {
            Client client = connect(new ServerChannelInitializer());

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
            Client link = connect(HubController.getInitializer());

            HubController.attach(link.channel);
            link.receive(Packets.REGISTER);

            Client client = connect(new ServerChannelInitializer());

            client.hello("Ratchet");
            client.send(new AccountPacket(Opcodes.Account.TOKEN, "token"));

            InputBuffer verify = link.receive(Packets.HUB);

            Assertions.assertEquals(Opcodes.Hub.VERIFY, Opcodes.Hub.get(verify.readUnsignedByte()));

            OutputBuffer answer = new OutputBuffer();

            answer.writeInt(verify.readInt());
            answer.writeLong(42L);
            answer.writeString("Ratchet");
            link.send(new HubPacket(Opcodes.Hub.VERIFIED, answer));

            InputBuffer loggedIn = client.receive(Packets.ACCOUNT);

            Assertions.assertEquals(Opcodes.Account.LOGGED_IN, Opcodes.Account.get(loggedIn.readUnsignedByte()));
            Assertions.assertEquals(42L, loggedIn.readLong());
        }

        @Test
        @DisplayName("Without a hub link a token is answered with try later")
        public void testNoHub() {
            Client client = connect(new ServerChannelInitializer());

            client.hello("Ratchet");
            client.send(new AccountPacket(Opcodes.Account.TOKEN, "token"));

            Assertions.assertEquals(Opcodes.Error.TRY_LATER, client.receiveError());
        }
    }
}
