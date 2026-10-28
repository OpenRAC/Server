package com.openrac.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openrac.net.NetworkConstants;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.out.ChatPacket;
import com.openrac.net.packets.out.DataPacket;
import com.openrac.net.packets.out.HelloPacket;
import com.openrac.net.packets.out.PartyPacket;
import com.openrac.net.packets.out.TimePacket;
import com.openrac.server.limits.ConnectionLimit;
import com.openrac.server.party.PartyController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs the server's real pipeline on embedded channels, one per player, and walks the party flow.
 */

public class PartyFlowTests {
    private final List<Client> clients = new ArrayList<>();

    /**
     * Disconnects every client, which also ends their parties.
     */

    @AfterEach
    public void closeClients() {
        for (Client client : clients) client.channel.close();
    }

    /**
     * Connects a player and says hello.
     * @param name The display name.
     * @return The greeted client.
     */

    private Client connect(String name) {
        Client client = new Client();

        clients.add(client);
        client.hello(name);

        return client;
    }

    @Nested
    @DisplayName("Hello Tests")
    class HelloTests {

        @Test
        @DisplayName("Each hello gets its own session id")
        public void testSessionIds() {
            Client first = connect("Ratchet");
            Client second = connect("Clank");

            Assertions.assertTrue(first.id > 0);
            Assertions.assertNotEquals(first.id, second.id);
        }

        @Test
        @DisplayName("A packet before hello closes the connection")
        public void testPacketBeforeHello() {
            Client client = new Client();

            clients.add(client);
            client.send(new PartyPacket(Opcodes.Party.CREATE));

            Assertions.assertFalse(client.channel.isActive());
        }

        @Test
        @DisplayName("A blank or overlong name closes the connection")
        public void testInvalidNames() {
            for (String name : new String[] { "   ", "x".repeat(Constants.MAX_NAME_LENGTH + 1) }) {
                Client client = new Client();

                clients.add(client);
                client.send(new HelloPacket("0.1.0", name));

                Assertions.assertFalse(client.channel.isActive());
            }
        }

        @Test
        @DisplayName("A frame over the limit closes the connection")
        public void testOversizedFrame() {
            Client client = connect("Qwark");

            client.send(new DataPacket(NetworkConstants.BROADCAST, new byte[NetworkConstants.MAX_FRAME_LENGTH]));

            Assertions.assertFalse(client.channel.isActive());
        }

        @Test
        @DisplayName("An unknown packet id closes the connection")
        public void testUnknownPacket() {
            Client client = connect("Nefarious");

            client.send(new OutgoingPacket(Packets.values().length));

            Assertions.assertFalse(client.channel.isActive());
        }
    }

    @Nested
    @DisplayName("Scenario Tests")
    class ScenarioTests {

        @Test
        @DisplayName("Create, join, chat, forward data, then the host leaves")
        public void testPartyFlow() {
            Client host = connect("Ratchet");
            Client first = connect("Clank");
            Client second = connect("Qwark");

            String code = host.create();

            Assertions.assertEquals(Constants.CODE_LENGTH, code.length());

            // Codes are typed by players, so case is ignored.
            first.join(code.toLowerCase(Locale.ROOT), 2);

            InputBuffer joined = host.receiveParty(Opcodes.Party.JOINED);

            Assertions.assertEquals(first.id, joined.readInt());
            Assertions.assertEquals("Clank", joined.readString());

            second.join(code, 3);
            host.receiveParty(Opcodes.Party.JOINED);
            first.receiveParty(Opcodes.Party.JOINED);

            // Chat reaches every member, the sender included.
            first.send(new ChatPacket("Hello"));

            for (Client client : List.of(host, first, second)) {
                InputBuffer chat = client.receive(Packets.CHAT);

                Assertions.assertEquals(first.id, chat.readInt());
                Assertions.assertEquals("Hello", chat.readString());
            }

            // A member's data goes to the host only, whatever its target.
            first.send(new DataPacket(second.id, new byte[] { 1, 2 }));

            InputBuffer toHost = host.receive(Packets.DATA);

            Assertions.assertEquals(first.id, toHost.readInt());
            Assertions.assertArrayEquals(new byte[] { 1, 2 }, toHost.readRemainingBytes());
            second.assertSilent();

            // The host's broadcast reaches every member but the host.
            host.send(new DataPacket(NetworkConstants.BROADCAST, new byte[] { 3 }));

            for (Client client : List.of(first, second)) {
                InputBuffer data = client.receive(Packets.DATA);

                Assertions.assertEquals(host.id, data.readInt());
                Assertions.assertArrayEquals(new byte[] { 3 }, data.readRemainingBytes());
            }

            host.assertSilent();

            // A targeted send reaches that member alone.
            host.send(new DataPacket(second.id, new byte[] { 4 }));

            Assertions.assertArrayEquals(new byte[] { 4 }, skipId(second.receive(Packets.DATA)).readRemainingBytes());
            first.assertSilent();

            // A member leaving is announced to those who stay.
            second.send(new PartyPacket(Opcodes.Party.LEAVE));

            Assertions.assertEquals(second.id, host.receiveParty(Opcodes.Party.LEFT).readInt());
            Assertions.assertEquals(second.id, first.receiveParty(Opcodes.Party.LEFT).readInt());

            // The host leaving ends the party for everyone.
            host.send(new PartyPacket(Opcodes.Party.LEAVE));

            first.receiveParty(Opcodes.Party.END);
            Assertions.assertFalse(PartyController.exists(code));

            first.send(new ChatPacket("Anyone?"));

            Assertions.assertEquals(Opcodes.Error.NOT_IN_PARTY, first.receiveError());
        }

        @Test
        @DisplayName("Joining an unknown, full or second party is refused")
        public void testJoinRefusals() {
            Client host = connect("Ratchet");
            String code = host.create();

            Client stranger = connect("Stranger");

            stranger.send(new PartyPacket(Opcodes.Party.JOIN, "NOPE00"));
            Assertions.assertEquals(Opcodes.Error.PARTY_NOT_FOUND, stranger.receiveError());

            host.send(new PartyPacket(Opcodes.Party.CREATE));
            Assertions.assertEquals(Opcodes.Error.ALREADY_IN_PARTY, host.receiveError());

            // Fill the party up to its limit.
            for (int i = 1; i < Constants.MAX_PARTY_SIZE; i++) connect("Member" + i).join(code, i + 1);

            stranger.send(new PartyPacket(Opcodes.Party.JOIN, code));
            Assertions.assertEquals(Opcodes.Error.PARTY_FULL, stranger.receiveError());
        }

        @Test
        @DisplayName("Match finding fills open parties and never private ones")
        public void testFind() {
            Client host = connect("Ratchet");

            host.create();

            // With only a private party open, the finder hosts a new open one.
            Client finder = connect("Clank");

            finder.send(new PartyPacket(Opcodes.Party.FIND));

            InputBuffer info = finder.receiveParty(Opcodes.Party.INFO);

            info.readString();
            Assertions.assertEquals(finder.id, info.readInt());
            Assertions.assertEquals(1, info.readUnsignedByte());
            host.assertSilent();

            // Later finders fill that party up to its limit.
            for (int i = 2; i <= Constants.MAX_PARTY_SIZE; i++) {
                Client member = connect("Member" + i);

                member.send(new PartyPacket(Opcodes.Party.FIND));

                InputBuffer joined = member.receiveParty(Opcodes.Party.INFO);

                joined.readString();
                Assertions.assertEquals(finder.id, joined.readInt());
                Assertions.assertEquals(i, joined.readUnsignedByte());
            }

            // A full open party sends the next finder to a party of its own.
            Client late = connect("Qwark");

            late.send(new PartyPacket(Opcodes.Party.FIND));

            InputBuffer own = late.receiveParty(Opcodes.Party.INFO);

            own.readString();
            Assertions.assertEquals(late.id, own.readInt());

            late.send(new PartyPacket(Opcodes.Party.FIND));
            Assertions.assertEquals(Opcodes.Error.ALREADY_IN_PARTY, late.receiveError());
        }

        @Test
        @DisplayName("A clock request is answered with its own time and the server's")
        public void testTime() {
            Client client = connect("Ratchet");

            client.send(new TimePacket(77L));

            InputBuffer time = client.receive(Packets.TIME);

            Assertions.assertEquals(77L, time.readLong());
            Assertions.assertTrue(time.readLong() >= 0);
        }

        @Test
        @DisplayName("A host disconnecting ends the party")
        public void testHostDisconnect() {
            Client host = connect("Ratchet");
            Client member = connect("Clank");
            String code = host.create();

            member.join(code, 2);

            host.channel.close();

            member.receiveParty(Opcodes.Party.END);
            Assertions.assertFalse(PartyController.exists(code));
        }
    }

    @Nested
    @DisplayName("Limit Tests")
    class LimitTests {

        @Test
        @DisplayName("Chat past its rate is dropped, then a flood closes the connection")
        public void testChatFlood() {
            Client host = connect("Ratchet");

            host.create();

            // A burst within the limit all goes through.
            for (int i = 0; i < Constants.CHAT_BURST; i++) {
                host.send(new ChatPacket("Hello " + i));
                host.receive(Packets.CHAT);
            }

            // The next message is over the rate: dropped, the link stays open.
            host.send(new ChatPacket("Too fast"));
            host.assertSilent();
            Assertions.assertTrue(host.channel.isActive());

            // A sustained flood runs out the strikes and is closed.
            for (int i = 0; i <= Constants.STRIKE_BURST && host.channel.isActive(); i++) host.send(new ChatPacket("Spam"));

            Assertions.assertFalse(host.channel.isActive());
        }

        @Test
        @DisplayName("An address may only hold so many connections")
        public void testConnectionsPerAddress() throws UnknownHostException {
            InetAddress address = InetAddress.getByName("192.0.2.7");

            for (int i = 0; i < Constants.MAX_CONNECTIONS_PER_ADDRESS; i++) Assertions.assertTrue(ConnectionLimit.open(address));

            Assertions.assertFalse(ConnectionLimit.open(address));

            // Closing one frees a place for the next.
            ConnectionLimit.close(address);
            Assertions.assertTrue(ConnectionLimit.open(address));

            for (int i = 0; i < Constants.MAX_CONNECTIONS_PER_ADDRESS; i++) ConnectionLimit.close(address);
        }
    }

    /**
     * Skips the session id at the front of a forwarded packet.
     * @param payload The payload.
     * @return The same payload, past the id.
     */

    private static InputBuffer skipId(InputBuffer payload) {
        payload.readInt();

        return payload;
    }
}
