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
import com.openrac.net.packets.out.HelloPacket;
import com.openrac.net.packets.out.PartyPacket;
import com.openrac.net.packets.out.RegionsPacket;
import com.openrac.net.packets.out.RegisterPacket;
import com.openrac.net.packets.out.RelayPacket;
import com.openrac.server.hub.Mode;
import com.openrac.server.party.PartyController;
import com.openrac.utils.Clock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Connects a player that says hello with client bits.
     * @param name The display name.
     * @param bits The HELLO client bits.
     * @return The greeted client.
     */

    private Client connectWith(String name, int bits) {
        Client client = new Client();

        clients.add(client);
        client.send(new HelloPacket("0.1.0", name, bits));
        client.readGreeting();

        return client;
    }

    /**
     * Connects a player that reads SLOTS and says hello.
     * @param name The display name.
     * @return The greeted client.
     */

    private Client connectSlotted(String name) {
        return connectWith(name, NetworkConstants.CLIENT_SLOTS);
    }

    /**
     * Connects a player that promises TIME every few seconds and says hello.
     * @param name The display name.
     * @return The greeted client.
     */

    private Client connectKeepingAlive(String name) {
        return connectWith(name, NetworkConstants.CLIENT_KEEPS_ALIVE);
    }

    /**
     * Reads a SLOTS notice.
     * @param client The client it was sent to.
     * @return Each member's slot by session id.
     */

    private static Map<Integer, Integer> readSlots(Client client) {
        InputBuffer payload = client.receiveParty(Opcodes.Party.SLOTS);
        Map<Integer, Integer> slots = new HashMap<>();
        int count = payload.readUnsignedByte();

        for (int i = 0; i < count; i++) slots.put(payload.readInt(), payload.readUnsignedByte());

        return slots;
    }

    /**
     * Builds a create request for a game family.
     * @param family The family.
     * @return The request.
     */

    private static PartyPacket createRequest(String family) {
        PartyPacket packet = new PartyPacket(Opcodes.Party.CREATE, "Room");

        packet.writeString(family);

        return packet;
    }

    /**
     * Builds a join request for a game family.
     * @param code The join code.
     * @param family The family.
     * @return The request.
     */

    private static PartyPacket joinRequest(String code, String family) {
        PartyPacket packet = new PartyPacket(Opcodes.Party.JOIN, code);

        packet.writeString(family);

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
            Assertions.assertEquals(8, Packets.REGIONS.ordinal());
            Assertions.assertEquals(9, Packets.REGISTER.ordinal());
            Assertions.assertEquals(14, Opcodes.Party.SLOTS.ordinal());
        }
    }

    @Nested
    @DisplayName("Mode Tests")
    class ModeTests {

        @Test
        @DisplayName("A mode reads in any case and an unknown one means all")
        public void testModes() {
            Assertions.assertEquals(Mode.HUB, Mode.get("hub"));
            Assertions.assertEquals(Mode.REGION, Mode.get(" Region "));
            Assertions.assertEquals(Mode.ALL, Mode.get("ALL"));
            Assertions.assertEquals(Mode.ALL, Mode.get("nonsense"));
        }

        @Test
        @DisplayName("A mode's name reads back to the same mode")
        public void testNames() {
            for (Mode mode : Mode.values()) Assertions.assertEquals(mode, Mode.get(mode.getName()));
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

    @Nested
    @DisplayName("Slot Tests")
    class SlotTests {

        @Test
        @DisplayName("Members keep their slots, and a newcomer takes the lowest free one")
        public void testSlots() {
            Client host = connectSlotted("Ratchet");
            Client first = connectSlotted("Clank");
            Client second = connectSlotted("Qwark");

            host.send(createRequest("rac3-pal-1"));

            String code = host.receiveParty(Opcodes.Party.INFO).readString();

            Assertions.assertEquals(Map.of(host.id, 0), readSlots(host));

            for (Client joiner : List.of(first, second)) {
                joiner.send(joinRequest(code, "rac3-pal-1"));
            }

            Assertions.assertEquals(1, readSlotsAfterJoin(first).get(first.id));
            Assertions.assertEquals(2, readSlotsAfterJoin(second).get(second.id));

            first.send(new PartyPacket(Opcodes.Party.LEAVE));

            Client third = connectSlotted("Skrunch");
            third.send(joinRequest(code, "rac3-pal-1"));

            Map<Integer, Integer> slots = readSlotsAfterJoin(third);

            Assertions.assertEquals(1, slots.get(third.id));
            Assertions.assertEquals(2, slots.get(second.id));
        }

        @Test
        @DisplayName("A family's own party size is what its listings say")
        public void testFamilySize() {
            Client host = connect("Ratchet");
            Client viewer = connect("Clank");

            host.send(createRequest("rac3-pal-1"));
            host.receiveParty(Opcodes.Party.INFO);

            PartyPacket list = new PartyPacket(Opcodes.Party.LIST, "rac3-pal-1");

            viewer.send(list);

            InputBuffer payload = viewer.receiveParty(Opcodes.Party.LIST);

            Assertions.assertEquals(1, payload.readUnsignedByte());
            payload.readString();
            payload.readString();
            payload.readUnsignedByte();
            Assertions.assertEquals(8, payload.readUnsignedByte());
        }

        /**
         * Skips a joiner's INFO and reads the SLOTS that follows it.
         * @param joiner The client that joined.
         * @return Each member's slot by session id.
         */

        private Map<Integer, Integer> readSlotsAfterJoin(Client joiner) {
            joiner.receiveParty(Opcodes.Party.INFO);

            return readSlots(joiner);
        }
    }

    @Nested
    @DisplayName("Region Tests")
    class RegionTests {

        /**
         * Asks for the regions and reads them.
         * @param client The client asking.
         * @return Each region's name and port.
         */

        private Map<String, Integer> regions(Client client) {
            client.send(new RegionsPacket("rac3-pal-1"));

            InputBuffer payload = client.receive(Packets.REGIONS);
            Map<String, Integer> found = new HashMap<>();
            int count = payload.readUnsignedByte();

            for (int i = 0; i < count; i++) {
                String name = payload.readString();

                payload.readString();
                found.put(name, payload.readInt());
                payload.readInt();
            }

            return found;
        }

        @Test
        @DisplayName("A single process lists itself as a region")
        public void testOwnRegion() {
            Assertions.assertEquals(Constants.PORT, regions(connect("Ratchet")).get(Constants.REGION_NAME));
        }

        @Test
        @DisplayName("A region server is listed while its connection lasts")
        public void testRegistered() {
            Client region = connect("region Polaris");
            Client player = connect("Clank");

            region.send(new RegisterPacket("", "Polaris", "polaris.example", 7800, 3, "Box"));

            Assertions.assertEquals(7800, regions(player).get("Polaris"));

            region.channel.close();

            Assertions.assertNull(regions(player).get("Polaris"));
        }
    }

    @Nested
    @DisplayName("Keep Alive Tests")
    class KeepAliveTests {

        /**
         * @return The sweep time just past the keep-alive idle limit.
         */

        private long pastIdleLimit() {
            return Clock.now() + Constants.KEEP_ALIVE_IDLE_SECONDS * 1000L + 1000L;
        }

        @Test
        @DisplayName("A keep-alive member gone quiet is dropped and the others hear it left")
        public void testQuietMember() {
            Client host = connect("Ratchet");
            Client member = connectKeepingAlive("Clank");

            party(host, member);
            PartyController.sweep(pastIdleLimit());

            Assertions.assertFalse(member.channel.isActive());
            Assertions.assertEquals(member.id, host.receiveParty(Opcodes.Party.LEFT).readInt());
        }

        @Test
        @DisplayName("A keep-alive host gone quiet leaves as a host does, ending a party nobody takes over")
        public void testQuietHost() {
            Client host = connectKeepingAlive("Ratchet");
            Client member = connect("Clank");

            party(host, member);
            PartyController.sweep(pastIdleLimit());

            Assertions.assertFalse(host.channel.isActive());
            member.receiveParty(Opcodes.Party.END);
        }

        @Test
        @DisplayName("A keep-alive member heard within the limit stays")
        public void testHeardMember() {
            Client host = connect("Ratchet");
            Client member = connectKeepingAlive("Clank");

            party(host, member);
            PartyController.sweep(Clock.now() + Constants.KEEP_ALIVE_IDLE_SECONDS * 1000L - 2000L);

            Assertions.assertTrue(member.channel.isActive());
            host.assertSilent();
        }

        @Test
        @DisplayName("A member that never promised TIME is never dropped for silence")
        public void testNoPromise() {
            Client host = connect("Ratchet");
            Client member = connect("Clank");

            party(host, member);
            PartyController.sweep(pastIdleLimit());

            Assertions.assertTrue(member.channel.isActive());
            host.assertSilent();
        }

        @Test
        @DisplayName("The keep-alive bit keeps its value")
        public void testBit() {
            Assertions.assertEquals(4, NetworkConstants.CLIENT_KEEPS_ALIVE);
        }
    }
}
