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
import com.openrac.net.limits.ConnectionLimit;
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
        @DisplayName("Match finding fills a waiting party, created or found, up to its limit")
        public void testFind() {
            Client host = connect("Ratchet");

            host.create();

            // A created party waits for finders as well as for its code.
            Client finder = connect("Clank");

            finder.send(new PartyPacket(Opcodes.Party.FIND));

            InputBuffer info = finder.receiveParty(Opcodes.Party.INFO);

            info.readString();
            Assertions.assertEquals(host.id, info.readInt());
            Assertions.assertEquals(2, info.readUnsignedByte());
            Assertions.assertEquals(finder.id, host.receiveParty(Opcodes.Party.JOINED).readInt());

            // Later finders fill that party up to its limit.
            for (int i = 3; i <= Constants.MAX_PARTY_SIZE; i++) {
                Client member = connect("Member" + i);

                member.send(new PartyPacket(Opcodes.Party.FIND));

                InputBuffer joined = member.receiveParty(Opcodes.Party.INFO);

                joined.readString();
                Assertions.assertEquals(host.id, joined.readInt());
                Assertions.assertEquals(i, joined.readUnsignedByte());
            }

            // A full party sends the next finder to a party of its own.
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
    @DisplayName("Kick Tests")
    class KickTests {

        @Test
        @DisplayName("The server says it takes a kick")
        public void testFeatureBit() {
            Assertions.assertEquals(NetworkConstants.FEATURE_KICK, connect("Ratchet").features & NetworkConstants.FEATURE_KICK);
        }

        @Test
        @DisplayName("Opcodes keep their numbers")
        public void testOrdinals() {
            Assertions.assertEquals(9, Opcodes.Party.HOST_CHANGED.ordinal());
            Assertions.assertEquals(10, Opcodes.Party.RESUME.ordinal());
            Assertions.assertEquals(11, Opcodes.Party.KICK.ordinal());
            Assertions.assertEquals(12, Opcodes.Party.KICKED.ordinal());
            Assertions.assertEquals(4, Opcodes.Error.NOT_HOST.ordinal());
        }

        @Test
        @DisplayName("The host removes a member: it is told, the others hear it left, it stays connected")
        public void testKick() {
            Client host = connect("Ratchet");
            Client first = connect("Clank");
            Client second = connect("Qwark");
            String code = host.create();

            first.join(code, 2);
            host.receiveParty(Opcodes.Party.JOINED);
            second.join(code, 3);
            host.receiveParty(Opcodes.Party.JOINED);
            first.receiveParty(Opcodes.Party.JOINED);

            host.send(new PartyPacket(Opcodes.Party.KICK, first.id));

            first.receiveParty(Opcodes.Party.KICKED);
            Assertions.assertEquals(first.id, host.receiveParty(Opcodes.Party.LEFT).readInt());
            Assertions.assertEquals(first.id, second.receiveParty(Opcodes.Party.LEFT).readInt());

            first.assertSilent();
            Assertions.assertTrue(first.channel.isActive());

            // Out of the party, its data goes nowhere and its code still works: a kick is no ban.
            first.send(new PartyPacket(Opcodes.Party.LEAVE));
            Assertions.assertEquals(Opcodes.Error.NOT_IN_PARTY, first.receiveError());

            first.join(code, 3);
            host.receiveParty(Opcodes.Party.JOINED);
            second.receiveParty(Opcodes.Party.JOINED);
        }

        @Test
        @DisplayName("Only the host removes, nobody removes the host, and a stranger's id does nothing")
        public void testRefusals() {
            Client host = connect("Ratchet");
            Client first = connect("Clank");
            Client second = connect("Qwark");
            Client outsider = connect("Skid");
            String code = host.create();

            first.join(code, 2);
            host.receiveParty(Opcodes.Party.JOINED);
            second.join(code, 3);
            host.receiveParty(Opcodes.Party.JOINED);
            first.receiveParty(Opcodes.Party.JOINED);

            first.send(new PartyPacket(Opcodes.Party.KICK, second.id));
            Assertions.assertEquals(Opcodes.Error.NOT_HOST, first.receiveError());

            first.send(new PartyPacket(Opcodes.Party.KICK, host.id));
            Assertions.assertEquals(Opcodes.Error.NOT_HOST, first.receiveError());

            outsider.send(new PartyPacket(Opcodes.Party.KICK, first.id));
            Assertions.assertEquals(Opcodes.Error.NOT_IN_PARTY, outsider.receiveError());

            // The host itself, and an id that is no member's: left alone, in silence.
            host.send(new PartyPacket(Opcodes.Party.KICK, host.id));
            host.send(new PartyPacket(Opcodes.Party.KICK, outsider.id));

            host.assertSilent();
            first.assertSilent();
            second.assertSilent();
            outsider.assertSilent();
        }

        @Test
        @DisplayName("Match finding does not put a removed player back in that party")
        public void testFindSkipsTheKicked() {
            Client host = connect("Ratchet");
            Client member = connect("Clank");
            String code = host.create();

            member.join(code, 2);
            host.receiveParty(Opcodes.Party.JOINED);
            host.send(new PartyPacket(Opcodes.Party.KICK, member.id));
            member.receiveParty(Opcodes.Party.KICKED);
            host.receiveParty(Opcodes.Party.LEFT);

            member.send(new PartyPacket(Opcodes.Party.FIND));

            InputBuffer info = member.receiveParty(Opcodes.Party.INFO);

            Assertions.assertNotEquals(code, info.readString());
            host.assertSilent();
        }
    }

    @Nested
    @DisplayName("Description Tests")
    class DescriptionTests {

        @Test
        @DisplayName("A created party's description comes back in its info")
        public void testDescriptionInInfo() {
            Client host = connect("Ratchet");

            host.send(new PartyPacket(Opcodes.Party.CREATE, "Chill run"));

            Assertions.assertEquals("Chill run", readInfoDescription(host.receiveParty(Opcodes.Party.INFO), 1));
        }

        @Test
        @DisplayName("An empty body or empty description means no description")
        public void testEmptyDescription() {
            Client older = connect("Ratchet");
            Client newer = connect("Clank");

            older.send(new PartyPacket(Opcodes.Party.CREATE));
            newer.send(new PartyPacket(Opcodes.Party.CREATE, ""));

            Assertions.assertEquals("", readInfoDescription(older.receiveParty(Opcodes.Party.INFO), 1));
            Assertions.assertEquals("", readInfoDescription(newer.receiveParty(Opcodes.Party.INFO), 1));
        }

        @Test
        @DisplayName("Only printable ASCII is kept, then the ends are trimmed")
        public void testSanitising() {
            Client host = connect("Ratchet");

            // Control characters, a tab, non-ASCII letters and an emoji all go.
            host.send(new PartyPacket(Opcodes.Party.CREATE, "  \u0001Hi\tthere \u00e9\u00fc\ud83d\ude00 all\u007f\n  "));

            Assertions.assertEquals("Hithere  all", readInfoDescription(host.receiveParty(Opcodes.Party.INFO), 1));
        }

        @Test
        @DisplayName("A description is cut to the limit")
        public void testOverlong() {
            Client host = connect("Ratchet");

            host.send(new PartyPacket(Opcodes.Party.CREATE, "x".repeat(Constants.MAX_DESCRIPTION_LENGTH + 20)));

            Assertions.assertEquals("x".repeat(Constants.MAX_DESCRIPTION_LENGTH), readInfoDescription(host.receiveParty(Opcodes.Party.INFO), 1));
        }

        @Test
        @DisplayName("Info to a joining member ends with the description after the members")
        public void testJoinInfoDescription() {
            Client host = connect("Ratchet");
            Client member = connect("Clank");

            host.send(new PartyPacket(Opcodes.Party.CREATE, "Chill run"));

            String code = host.receiveParty(Opcodes.Party.INFO).readString();

            member.send(new PartyPacket(Opcodes.Party.JOIN, code));

            InputBuffer info = member.receiveParty(Opcodes.Party.INFO);

            Assertions.assertEquals("Chill run", readInfoDescription(info, 2));
        }

        @Test
        @DisplayName("A party made by match finding has an empty description")
        public void testFindDescription() {
            Client finder = connect("Ratchet");

            finder.send(new PartyPacket(Opcodes.Party.FIND));

            Assertions.assertEquals("", readInfoDescription(finder.receiveParty(Opcodes.Party.INFO), 1));
        }
    }

    @Nested
    @DisplayName("List Tests")
    class ListTests {

        @Test
        @DisplayName("An empty list is a zero count")
        public void testEmptyList() {
            Client client = connect("Ratchet");

            Assertions.assertEquals(0, list(client).size());
        }

        @Test
        @DisplayName("A listing carries the code, host name, counts and description")
        public void testListingLayout() {
            Client host = connect("Ratchet");
            Client viewer = connect("Clank");

            host.send(new PartyPacket(Opcodes.Party.CREATE, "Chill run"));

            String code = host.receiveParty(Opcodes.Party.INFO).readString();

            viewer.send(new PartyPacket(Opcodes.Party.LIST));

            InputBuffer payload = viewer.receiveParty(Opcodes.Party.LIST);

            Assertions.assertEquals(1, payload.readUnsignedByte());
            Assertions.assertEquals(code, payload.readString());
            Assertions.assertEquals("Ratchet", payload.readString());
            Assertions.assertEquals(1, payload.readUnsignedByte());
            Assertions.assertEquals(Constants.MAX_PARTY_SIZE, payload.readUnsignedByte());
            Assertions.assertEquals("Chill run", payload.readString());
            Assertions.assertEquals(0, payload.size());
        }

        @Test
        @DisplayName("Parties come oldest first, created and found alike")
        public void testOrder() {
            Client first = connect("Ratchet");
            Client second = connect("Clank");
            Client viewer = connect("Qwark");

            first.send(new PartyPacket(Opcodes.Party.CREATE, "First"));
            second.send(new PartyPacket(Opcodes.Party.CREATE, "Second"));

            String firstCode = first.receiveParty(Opcodes.Party.INFO).readString();
            String secondCode = second.receiveParty(Opcodes.Party.INFO).readString();

            List<Listing> listed = list(viewer);

            Assertions.assertEquals(2, listed.size());
            Assertions.assertEquals(firstCode, listed.get(0).code());
            Assertions.assertEquals("First", listed.get(0).description());
            Assertions.assertEquals(secondCode, listed.get(1).code());

            // A party that match finding made is listed too, with no description.
            Client finder = connect("Nefarious");

            first.channel.close();
            second.channel.close();
            finder.send(new PartyPacket(Opcodes.Party.FIND));

            String foundCode = finder.receiveParty(Opcodes.Party.INFO).readString();

            listed = list(viewer);

            Assertions.assertEquals(1, listed.size());
            Assertions.assertEquals(foundCode, listed.get(0).code());
            Assertions.assertEquals("", listed.get(0).description());
        }

        @Test
        @DisplayName("Full parties and the requester's own party are left out")
        public void testFilters() {
            Client host = connect("Ratchet");
            String code = host.create();
            Client viewer = connect("Clank");

            // The host is in the only party, so it has nothing to see.
            Assertions.assertEquals(0, list(host).size());
            Assertions.assertEquals(1, list(viewer).size());

            // Members count as room taken, and the count follows.
            viewer.join(code, 2);
            Assertions.assertEquals(0, list(viewer).size());

            Client outsider = connect("Qwark");

            Assertions.assertEquals(2, list(outsider).get(0).members());

            for (int i = 2; i < Constants.MAX_PARTY_SIZE; i++) connect("Member" + i).join(code, i + 1);

            Assertions.assertEquals(0, list(outsider).size());
        }

        @Test
        @DisplayName("A member sees other parties with room, not its own")
        public void testMemberSeesOthers() {
            Client host = connect("Ratchet");
            Client other = connect("Clank");
            Client member = connect("Qwark");

            member.join(host.create(), 2);

            String otherCode = other.create();
            List<Listing> listed = list(member);

            Assertions.assertEquals(1, listed.size());
            Assertions.assertEquals(otherCode, listed.get(0).code());
        }

        @Test
        @DisplayName("A list carries at most the listed-parties limit")
        public void testCap() {
            for (int i = 0; i < Constants.MAX_LISTED_PARTIES + 3; i++) connect("Host" + i).create();

            Assertions.assertEquals(Constants.MAX_LISTED_PARTIES, list(connect("Viewer")).size());
        }
    }

    @Nested
    @DisplayName("Family Tests")
    class FamilyTests {
        private static final String RAC1 = Constants.DEFAULT_FAMILY;
        private static final String RAC2 = "rac2-ntsc-1";

        @Test
        @DisplayName("A list shows only the asker's game family, and none named is Ratchet & Clank 1")
        public void testListByFamily() {
            String older = connect("Ratchet").create();
            String newer = create(connect("Clank"), "Veldin", RAC1);
            String rac2 = create(connect("Qwark"), "Oozla", RAC2);

            create(connect("Angela"), "Endako", "rac2-pal-1");

            Client viewer = connect("Viewer");

            Assertions.assertEquals(List.of(older, newer), codes(list(viewer)));
            Assertions.assertEquals(List.of(older, newer), codes(list(viewer, RAC1)));
            Assertions.assertEquals(List.of(rac2), codes(list(viewer, RAC2)));
            Assertions.assertEquals(List.of(), codes(list(viewer, "rac3-1")));
        }

        @Test
        @DisplayName("A join by code into another family is refused with its family, or as not found for an older client")
        public void testJoinOtherFamily() {
            String rac2 = create(connect("Qwark"), "Oozla", RAC2);
            String rac1 = connect("Ratchet").create();

            Client newer = connect("Clank");

            newer.send(join(rac2, RAC1));

            InputBuffer error = newer.receive(Packets.ERROR);

            Assertions.assertEquals(Opcodes.Error.OTHER_GAME, Opcodes.Error.get(error.readUnsignedByte()));
            Assertions.assertEquals(RAC2, error.readString());
            Assertions.assertEquals(0, error.size());

            // An older client knows no such reason, and is a Ratchet & Clank 1 game anyway.
            Client older = connect("Angela");

            older.send(new PartyPacket(Opcodes.Party.JOIN, rac2));
            Assertions.assertEquals(Opcodes.Error.PARTY_NOT_FOUND, older.receiveError());

            // A Going Commando game cannot join a Ratchet & Clank 1 party either.
            Client going = connect("Nefarious");

            going.send(join(rac1, RAC2));
            Assertions.assertEquals(Opcodes.Error.OTHER_GAME, Opcodes.Error.get(going.receive(Packets.ERROR).readUnsignedByte()));

            // Its own family's party takes it, as the code is the same.
            going.send(join(rac2, RAC2));
            Assertions.assertEquals(rac2, going.receiveParty(Opcodes.Party.INFO).readString());
        }

        @Test
        @DisplayName("Older and newer Ratchet & Clank 1 clients still play together")
        public void testOlderClients() {
            String older = connect("Ratchet").create();
            String newer = create(connect("Clank"), "Veldin", RAC1);

            Client member = connect("Qwark");

            member.send(join(older, RAC1));
            Assertions.assertEquals(older, member.receiveParty(Opcodes.Party.INFO).readString());

            connect("Angela").join(newer, 2);
        }

        @Test
        @DisplayName("Match finding only fills a party of the finder's family")
        public void testFindByFamily() {
            Client host = connect("Ratchet");
            String rac1 = host.create();

            Client going = connect("Qwark");
            PartyPacket find = new PartyPacket(Opcodes.Party.FIND);

            find.writeString(RAC2);
            going.send(find);

            InputBuffer own = going.receiveParty(Opcodes.Party.INFO);
            String rac2 = own.readString();

            Assertions.assertNotEquals(rac1, rac2);
            Assertions.assertEquals(going.id, own.readInt());

            // An older finder is a Ratchet & Clank 1 game: the Ratchet & Clank 1 party.
            Client older = connect("Clank");

            older.send(new PartyPacket(Opcodes.Party.FIND));
            Assertions.assertEquals(rac1, older.receiveParty(Opcodes.Party.INFO).readString());

            Assertions.assertEquals(List.of(rac2), codes(list(connect("Viewer"), RAC2)));
        }

        @Test
        @DisplayName("A family with a space or over the limit closes the connection")
        public void testInvalidFamily() {
            for (String family : new String[] { "rac 1", "x".repeat(Constants.MAX_FAMILY_LENGTH + 1) }) {
                Client client = connect("Ratchet");

                client.send(join("ABCDEF", family));

                Assertions.assertFalse(client.channel.isActive());
            }
        }

        /**
         * Creates a party as a client that names its family.
         * @param client The host.
         * @param description The party's description.
         * @param family The host's game family.
         * @return The party's join code.
         */

        private static String create(Client client, String description, String family) {
            PartyPacket create = new PartyPacket(Opcodes.Party.CREATE, description);

            create.writeString(family);
            client.send(create);

            return client.receiveParty(Opcodes.Party.INFO).readString();
        }

        /**
         * @param code The join code.
         * @param family The joiner's game family.
         * @return A JOIN that names its family.
         */

        private static PartyPacket join(String code, String family) {
            PartyPacket join = new PartyPacket(Opcodes.Party.JOIN, code);

            join.writeString(family);

            return join;
        }

        /**
         * @param listed Parties as listed.
         * @return Their codes, in order.
         */

        private static List<String> codes(List<Listing> listed) {
            return listed.stream().map(Listing::code).toList();
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
        @DisplayName("List requests share the party rate, then are dropped and struck")
        public void testListFlood() {
            Client client = connect("Ratchet");

            // A burst within the limit all gets answers.
            for (int i = 0; i < Constants.PARTY_BURST; i++) list(client);

            // The next request is over the rate: dropped, the link stays open.
            client.send(new PartyPacket(Opcodes.Party.LIST));
            client.assertSilent();
            Assertions.assertTrue(client.channel.isActive());

            // A sustained flood runs out the strikes and is closed.
            for (int i = 0; i <= Constants.STRIKE_BURST && client.channel.isActive(); i++) client.send(new PartyPacket(Opcodes.Party.LIST));

            Assertions.assertFalse(client.channel.isActive());
        }

        @Test
        @DisplayName("An address may only hold so many connections")
        public void testConnectionsPerAddress() throws UnknownHostException {
            InetAddress address = InetAddress.getByName("192.0.2.7");

            for (int i = 0; i < Constants.MAX_CONNECTIONS_PER_ADDRESS; i++) Assertions.assertTrue(ConnectionLimit.open(address, Constants.MAX_CONNECTIONS_PER_ADDRESS));

            Assertions.assertFalse(ConnectionLimit.open(address, Constants.MAX_CONNECTIONS_PER_ADDRESS));

            // Closing one frees a place for the next.
            ConnectionLimit.close(address);
            Assertions.assertTrue(ConnectionLimit.open(address, Constants.MAX_CONNECTIONS_PER_ADDRESS));

            for (int i = 0; i < Constants.MAX_CONNECTIONS_PER_ADDRESS; i++) ConnectionLimit.close(address);
        }
    }

    /**
     * A party as one LIST entry shows it.
     * @param code The join code.
     * @param host The host's name.
     * @param members The member count.
     * @param maxSize The size limit.
     * @param description The description.
     */

    private record Listing(String code, String host, int members, int maxSize, String description) {}

    /**
     * Asks for the party list and reads the answer.
     * @param client The client asking.
     * @return The listed parties, in the order sent.
     */

    private static List<Listing> list(Client client) {
        return read(client, new PartyPacket(Opcodes.Party.LIST));
    }

    /**
     * Asks for the party list as a client that names its game family, and reads the answer.
     * @param client The client asking.
     * @param family Its game family.
     * @return The listed parties, in the order sent.
     */

    private static List<Listing> list(Client client, String family) {
        return read(client, new PartyPacket(Opcodes.Party.LIST, family));
    }

    /**
     * Sends a list request and reads the answer.
     * @param client The client asking.
     * @param request The LIST packet.
     * @return The listed parties, in the order sent.
     */

    private static List<Listing> read(Client client, PartyPacket request) {
        client.send(request);

        InputBuffer payload = client.receiveParty(Opcodes.Party.LIST);
        int count = payload.readUnsignedByte();
        List<Listing> listed = new ArrayList<>();

        for (int i = 0; i < count; i++)
            listed.add(new Listing(payload.readString(), payload.readString(), payload.readUnsignedByte(), payload.readUnsignedByte(), payload.readString()));

        Assertions.assertEquals(0, payload.size());

        return listed;
    }

    /**
     * Reads a whole INFO payload and checks its member count.
     * @param info The INFO payload.
     * @param members How many members the party has.
     * @return The description after the members.
     */

    private static String readInfoDescription(InputBuffer info, int members) {
        info.readString();
        info.readInt();

        Assertions.assertEquals(members, info.readUnsignedByte());

        for (int i = 0; i < members; i++) {
            info.readInt();
            info.readString();
        }

        String description = info.readString();

        Assertions.assertEquals(0, info.size());

        return description;
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
