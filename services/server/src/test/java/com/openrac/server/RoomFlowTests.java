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
import com.openrac.net.packets.out.PartyPacket;
import com.openrac.net.packets.out.RelayPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the pieces games with lobby rooms use on the real pipeline: relayed data between any
 * members, and the advert a host lists its party with.
 */

public class RoomFlowTests {
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

    /**
     * Builds a party of a host and members, and drains the join notices.
     * @param host The host, who creates the party.
     * @param members The members, who join in order.
     */

    private static void party(Client host, Client... members) {
        String code = host.create();

        for (int i = 0; i < members.length; i++) {
            members[i].join(code, i + 2);

            // Everyone already in hears of each new member.
            host.receiveParty(Opcodes.Party.JOINED);

            for (int j = 0; j < i; j++) members[j].receiveParty(Opcodes.Party.JOINED);
        }
    }

    /**
     * Builds a party request for the default family with the given list flags.
     * @param flags The LIST flag bits.
     * @return The request.
     */

    private static PartyPacket listRequest(int flags) {
        PartyPacket packet = new PartyPacket(Opcodes.Party.LIST, Constants.DEFAULT_FAMILY);

        packet.writeUnsignedByte(flags);

        return packet;
    }

    /**
     * Builds an advert request.
     * @param bytes The advert.
     * @return The request.
     */

    private static PartyPacket advertRequest(byte[] bytes) {
        PartyPacket packet = new PartyPacket(Opcodes.Party.ADVERT);

        packet.writeBytes(bytes);

        return packet;
    }

    @Nested
    @DisplayName("Wire Tests")
    class WireTests {

        @Test
        @DisplayName("The server says it takes adverts and relays")
        public void testFeatureBits() {
            int features = connect("Ratchet").features;

            Assertions.assertEquals(NetworkConstants.FEATURE_ADVERT, features & NetworkConstants.FEATURE_ADVERT);
            Assertions.assertEquals(NetworkConstants.FEATURE_RELAY, features & NetworkConstants.FEATURE_RELAY);
        }

        @Test
        @DisplayName("The new packet and opcode keep their numbers")
        public void testOrdinals() {
            Assertions.assertEquals(7, Packets.RELAY.ordinal());
            Assertions.assertEquals(13, Opcodes.Party.ADVERT.ordinal());
        }
    }

    @Nested
    @DisplayName("Relay Tests")
    class RelayTests {

        @Test
        @DisplayName("A member's broadcast reaches every other member, the host included")
        public void testBroadcast() {
            Client host = connect("Ratchet");
            Client first = connect("Clank");
            Client second = connect("Qwark");

            party(host, first, second);

            first.send(new RelayPacket(NetworkConstants.BROADCAST, new byte[] {1, 2, 3}));

            for (Client other : List.of(host, second)) {
                InputBuffer payload = other.receive(Packets.RELAY);

                Assertions.assertEquals(first.id, payload.readInt());
                Assertions.assertArrayEquals(new byte[] {1, 2, 3}, payload.readRemainingBytes());
            }

            first.assertSilent();
        }

        @Test
        @DisplayName("A relay to one member reaches only that member")
        public void testDirected() {
            Client host = connect("Ratchet");
            Client first = connect("Clank");
            Client second = connect("Qwark");

            party(host, first, second);

            first.send(new RelayPacket(second.id, new byte[] {9}));

            InputBuffer payload = second.receive(Packets.RELAY);

            Assertions.assertEquals(first.id, payload.readInt());
            Assertions.assertArrayEquals(new byte[] {9}, payload.readRemainingBytes());

            host.assertSilent();
            first.assertSilent();
        }

        @Test
        @DisplayName("A relay outside a party goes nowhere")
        public void testOutsideParty() {
            Client loner = connect("Ratchet");

            loner.send(new RelayPacket(NetworkConstants.BROADCAST, new byte[] {1}));

            loner.assertSilent();
            Assertions.assertTrue(loner.channel.isActive());
        }
    }

    @Nested
    @DisplayName("Advert Tests")
    class AdvertTests {

        @Test
        @DisplayName("A listing asked with adverts ends with the host's advert")
        public void testListedAdvert() {
            Client host = connect("Ratchet");
            Client viewer = connect("Clank");

            host.create();
            host.send(advertRequest(new byte[] {5, 6, 7}));

            viewer.send(listRequest(NetworkConstants.LIST_ADVERTS));

            InputBuffer payload = viewer.receiveParty(Opcodes.Party.LIST);

            Assertions.assertEquals(1, payload.readUnsignedByte());
            payload.readString();
            Assertions.assertEquals("Ratchet", payload.readString());
            payload.readUnsignedByte();
            payload.readUnsignedByte();
            payload.readString();
            Assertions.assertEquals(0, payload.readUnsignedByte());
            Assertions.assertEquals(3, payload.readUnsignedByte());
            Assertions.assertArrayEquals(new byte[] {5, 6, 7}, payload.readRemainingBytes());
        }

        @Test
        @DisplayName("A listing asked without adverts keeps its old layout")
        public void testListWithoutAdverts() {
            Client host = connect("Ratchet");
            Client viewer = connect("Clank");

            host.create();
            host.send(advertRequest(new byte[] {5}));

            viewer.send(listRequest(0));

            InputBuffer payload = viewer.receiveParty(Opcodes.Party.LIST);

            Assertions.assertEquals(1, payload.readUnsignedByte());
            payload.readString();
            payload.readString();
            payload.readUnsignedByte();
            payload.readUnsignedByte();
            payload.readString();
            Assertions.assertEquals(0, payload.size());
        }

        @Test
        @DisplayName("Only a host in a party sets an advert")
        public void testRefusals() {
            Client loner = connect("Ratchet");

            loner.send(advertRequest(new byte[] {1}));
            Assertions.assertEquals(Opcodes.Error.NOT_IN_PARTY, loner.receiveError());

            Client host = connect("Clank");
            Client member = connect("Qwark");

            party(host, member);

            member.send(advertRequest(new byte[] {1}));
            Assertions.assertEquals(Opcodes.Error.NOT_HOST, member.receiveError());
        }

        @Test
        @DisplayName("An advert over the limit closes the connection")
        public void testOversized() {
            Client host = connect("Ratchet");

            host.create();
            host.send(advertRequest(new byte[Constants.MAX_ADVERT_LENGTH + 1]));

            Assertions.assertFalse(host.channel.isActive());
        }
    }
}
