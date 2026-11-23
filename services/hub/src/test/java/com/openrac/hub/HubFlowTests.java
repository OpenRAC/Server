package com.openrac.hub;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openrac.hub.accounts.AccountController;
import com.openrac.hub.accounts.MemoryAccountStore;
import com.openrac.hub.stats.MemoryStatsStore;
import com.openrac.hub.stats.StatsController;
import com.openrac.hub.servers.ServerController;
import com.openrac.net.NetworkConstants;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.GameResult;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.model.OutputBuffer;
import com.openrac.net.packets.out.AccountPacket;
import com.openrac.net.packets.out.HubPacket;
import com.openrac.net.packets.out.PartyPacket;
import com.openrac.net.packets.out.RegionsPacket;
import com.openrac.net.packets.out.RegisterPacket;
import com.openrac.net.packets.out.ServersPacket;
import com.openrac.net.packets.out.StatsPacket;
import com.openrac.utils.Clock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Runs the hub on its real pipeline: game servers registering and reporting, the list clients read
 * and watch, and account logins.
 */

public class HubFlowTests {
    private final List<Peer> peers = new ArrayList<>();

    /**
     * Gives each test empty accounts and stats, worked on the calling thread.
     */

    @BeforeEach
    public void resetAccounts() {
        AccountController.setStore(new MemoryAccountStore());
        StatsController.setStore(new MemoryStatsStore());
        Background.setExecutor(Runnable::run);
    }

    /**
     * Closes every peer, which also takes their servers off the list.
     */

    @AfterEach
    public void closePeers() {
        for (Peer peer : peers) peer.channel.close();
    }

    /**
     * @return A new connection to the hub.
     */

    private Peer connect() {
        Peer peer = new Peer();

        peers.add(peer);

        return peer;
    }

    /**
     * @param name The display name.
     * @return A client that said hello.
     */

    private Peer guest(String name) {
        Peer peer = connect();

        peer.hello(name);

        return peer;
    }

    /**
     * Asks for the regions and reads them.
     * @param guest The client asking.
     * @return Each region's port by its name.
     */

    private static Map<String, Integer> regions(Peer guest) {
        guest.send(new RegionsPacket("rac3-pal-1"));

        InputBuffer payload = guest.receive(Packets.REGIONS);
        Map<String, Integer> found = new HashMap<>();
        int count = payload.readUnsignedByte();

        for (int i = 0; i < count; i++) {
            String name = payload.readString();

            Assertions.assertEquals("", payload.readString());
            found.put(name, payload.readInt());
            payload.readInt();
        }

        return found;
    }

    /**
     * Logs in and reads the answer.
     * @param guest The client logging in.
     * @param name The account name.
     * @param password The password.
     * @return The LOGGED_IN payload after its opcode.
     */

    private static InputBuffer login(Peer guest, String name, String password) {
        guest.send(new AccountPacket(name, password));

        InputBuffer payload = guest.receive(Packets.ACCOUNT);

        Assertions.assertEquals(Opcodes.Account.LOGGED_IN, Opcodes.Account.get(payload.readUnsignedByte()));

        return payload;
    }

    @Nested
    @DisplayName("Registration Tests")
    class RegistrationTests {

        @Test
        @DisplayName("A client is told the hub lists and streams servers")
        public void testFeatures() {
            int features = connect().hello("Ratchet");

            Assertions.assertEquals(NetworkConstants.FEATURE_REGIONS | NetworkConstants.FEATURE_SERVERS, features);
        }

        @Test
        @DisplayName("A registered server is listed while its connection lasts")
        public void testRegistered() {
            Peer server = connect();
            Peer player = guest("Clank");

            server.register("US", 7800);

            Assertions.assertEquals(7800, regions(player).get("US"));

            server.channel.close();

            Assertions.assertNull(regions(player).get("US"));
        }

        @Test
        @DisplayName("A server is closed for a second registration or a bad port")
        public void testInvalid() {
            Peer twice = connect();

            twice.register("US", 7800);
            twice.send(new RegisterPacket("", "US", "", 7800, 0, "box"));

            Assertions.assertFalse(twice.channel.isOpen());

            Peer badPort = connect();

            badPort.send(new RegisterPacket("", "CA", "", 0, 0, "box"));

            Assertions.assertFalse(badPort.channel.isOpen());
        }

        @Test
        @DisplayName("A region must be a known code, in any case, and a name must fit the cap")
        public void testRegionCodes() {
            Peer unknown = connect();

            unknown.send(new RegisterPacket("", "Polaris", "", 7800, 0, "box"));

            Assertions.assertFalse(unknown.channel.isOpen());

            Peer longName = connect();

            longName.send(new RegisterPacket("", "US", "", 7800, 0, "x".repeat(NetworkConstants.MAX_SERVER_NAME_LENGTH + 1)));

            Assertions.assertFalse(longName.channel.isOpen());

            Peer lowerCase = connect();

            lowerCase.send(new RegisterPacket("", "de", "", 7802, 0, "x".repeat(NetworkConstants.MAX_SERVER_NAME_LENGTH)));
            lowerCase.receiveHub(Opcodes.Hub.REGISTERED);

            Assertions.assertEquals(7802, regions(guest("Clank")).get("DE"));
        }

        @Test
        @DisplayName("The region list carries each server's code, then every server's name")
        public void testListing() {
            connect().register("US", 7800);

            Peer unnamed = connect();

            unnamed.send(new RegisterPacket("", "GB", "", 7801, 0, ""));
            unnamed.receiveHub(Opcodes.Hub.REGISTERED);

            Peer player = guest("Clank");

            player.send(new RegionsPacket("rac3-pal-1"));

            InputBuffer payload = player.receive(Packets.REGIONS);

            Assertions.assertEquals(2, payload.readUnsignedByte());

            for (String code : List.of("US", "GB")) {
                Assertions.assertEquals(code, payload.readString());
                Assertions.assertEquals("", payload.readString());
                payload.readInt();
                payload.readInt();
            }

            Assertions.assertEquals("US box", payload.readString());
            Assertions.assertEquals("", payload.readString());
            Assertions.assertEquals(0, payload.size());
        }

        @Test
        @DisplayName("Clients cannot speak for servers, nor ask the hub for parties")
        public void testGuestLimits() {
            Peer spoof = guest("Ratchet");

            spoof.send(new HubPacket(5, 1));

            Assertions.assertFalse(spoof.channel.isOpen());

            Peer party = guest("Clank");

            party.send(new PartyPacket(Opcodes.Party.CREATE));

            Assertions.assertFalse(party.channel.isOpen());
        }
    }

    @Nested
    @DisplayName("Heartbeat Tests")
    class HeartbeatTests {

        @Test
        @DisplayName("Watchers hear a changed count and not an unchanged one")
        public void testChanged() {
            Peer server = connect();
            int id = server.register("US", 7800);
            Peer watcher = guest("Clank");

            watcher.send(new ServersPacket("rac3-pal-1"));
            watcher.receiveServers(Opcodes.Servers.ADDED);

            server.send(new HubPacket(3, 1));

            InputBuffer changed = watcher.receiveServers(Opcodes.Servers.CHANGED);

            Assertions.assertEquals(id, changed.readInt());
            Assertions.assertEquals(3, changed.readInt());
            Assertions.assertEquals(1, changed.readInt());

            server.send(new HubPacket(3, 1));
            watcher.assertSilent();
        }

        @Test
        @DisplayName("A server that goes quiet is dropped and its watchers told")
        public void testSilence() {
            Peer server = connect();
            int id = server.register("US", 7800);
            Peer watcher = guest("Clank");

            watcher.send(new ServersPacket("rac3-pal-1"));
            watcher.receiveServers(Opcodes.Servers.ADDED);

            // Still within the timeout, so it stays.
            ServerController.sweep(Clock.now() + Constants.SERVER_TIMEOUT_SECONDS * 1000L - 2000L);
            watcher.assertSilent();

            ServerController.sweep(Clock.now() + Constants.SERVER_TIMEOUT_SECONDS * 1000L + 1000L);

            Assertions.assertEquals(id, watcher.receiveServers(Opcodes.Servers.REMOVED).readInt());
            Assertions.assertFalse(server.channel.isOpen());
        }

        @Test
        @DisplayName("A closing server leaves the list at once")
        public void testClosing() {
            Peer server = connect();

            server.register("US", 7800);
            server.send(new HubPacket(Opcodes.Hub.CLOSING));

            Assertions.assertNull(regions(guest("Clank")).get("US"));
        }
    }

    @Nested
    @DisplayName("Watch Tests")
    class WatchTests {

        @Test
        @DisplayName("A watcher gets every server, then each new one")
        public void testWatch() {
            connect().register("US", 7800);

            Peer watcher = guest("Clank");

            watcher.send(new ServersPacket("rac3-pal-1"));

            InputBuffer added = watcher.receiveServers(Opcodes.Servers.ADDED);

            added.readInt();
            Assertions.assertEquals("US", added.readString());
            Assertions.assertEquals("US box", added.readString());
            Assertions.assertEquals("", added.readString());
            Assertions.assertEquals(7800, added.readInt());

            connect().register("CA", 7801);

            InputBuffer next = watcher.receiveServers(Opcodes.Servers.ADDED);

            next.readInt();
            Assertions.assertEquals("CA", next.readString());
        }
    }

    @Nested
    @DisplayName("Account Tests")
    class AccountTests {

        @Test
        @DisplayName("A first login registers, a later one logs in in any case")
        public void testRegisterThenLogin() {
            InputBuffer first = login(guest("Ratchet"), "Ratchet", "lombax");
            long id = first.readLong();

            Assertions.assertEquals("Ratchet", first.readString());
            Assertions.assertFalse(first.readString().isEmpty());
            Assertions.assertEquals(1, first.readUnsignedByte());

            InputBuffer again = login(guest("Ratchet"), "RATCHET", "lombax");

            Assertions.assertEquals(id, again.readLong());
            Assertions.assertEquals("Ratchet", again.readString());
            again.readString();
            Assertions.assertEquals(0, again.readUnsignedByte());
        }

        @Test
        @DisplayName("A wrong password and a bad name are refused")
        public void testRefused() {
            login(guest("Ratchet"), "Ratchet", "lombax");

            Peer thief = guest("Nefarious");

            thief.send(new AccountPacket("Ratchet", "robots"));
            Assertions.assertEquals(Opcodes.Error.WRONG_PASSWORD, thief.receiveError());

            thief.send(new AccountPacket(" Ratchet", "lombax"));
            Assertions.assertEquals(Opcodes.Error.INVALID_ACCOUNT, thief.receiveError());

            thief.send(new AccountPacket("Qwark", "no"));
            Assertions.assertEquals(Opcodes.Error.INVALID_ACCOUNT, thief.receiveError());
        }

        @Test
        @DisplayName("A server learns whose token it is, until the player logs out")
        public void testVerify() {
            Peer player = guest("Ratchet");
            InputBuffer loggedIn = login(player, "Ratchet", "lombax");
            long id = loggedIn.readLong();

            loggedIn.readString();

            String token = loggedIn.readString();
            Peer server = connect();

            server.register("US", 7800);
            server.send(new HubPacket(4, token));

            InputBuffer verified = server.receiveHub(Opcodes.Hub.VERIFIED);

            Assertions.assertEquals(4, verified.readInt());
            Assertions.assertEquals(id, verified.readLong());
            Assertions.assertEquals("Ratchet", verified.readString());

            player.send(new AccountPacket(Opcodes.Account.LOGOUT, token));
            server.send(new HubPacket(5, token));

            InputBuffer gone = server.receiveHub(Opcodes.Hub.VERIFIED);

            Assertions.assertEquals(5, gone.readInt());
            Assertions.assertEquals(0L, gone.readLong());
        }
    }

    @Nested
    @DisplayName("Stats Tests")
    class StatsTests {

        /**
         * Logs a new player in.
         * @param name The account name.
         * @return The client and its account id.
         */

        private Player player(String name) {
            Peer peer = guest(name);

            return new Player(peer, login(peer, name, "lombax").readLong());
        }

        /**
         * Sends a stats request and reads the answer.
         * @param peer The client asking.
         * @param opcode The stats opcode.
         * @param fields The request's fields after the family.
         * @return The answer's payload after its opcode.
         */

        private InputBuffer ask(Peer peer, Opcodes.Stats opcode, OutputBuffer fields) {
            OutputBuffer request = new OutputBuffer();

            request.writeString("rac3-pal-1");
            request.append(fields);
            peer.send(new StatsPacket(opcode, request));

            InputBuffer payload = peer.receive(Packets.STATS);

            Assertions.assertEquals(opcode, Opcodes.Stats.get(payload.readUnsignedByte()));

            return payload;
        }

        /**
         * Writes a player's ladder values.
         * @param player The player.
         * @param values Its values.
         */

        private void writeLadder(Player player, int... values) {
            OutputBuffer fields = new OutputBuffer();

            fields.writeInt(0);
            fields.writeUnsignedByte(values.length);

            for (int value : values) fields.writeInt(value);

            ask(player.peer(), Opcodes.Stats.LADDER_WRITE, fields);
        }

        @Test
        @DisplayName("A reported game adds to its players' totals, guests left out")
        public void testResult() {
            Player ratchet = player("Ratchet");
            Peer server = connect();

            server.register("US", 7800);

            GameResult.Player winner = new GameResult.Player(ratchet.id(), "Ratchet", 0, 12, 3, 1200, true);
            GameResult.Player guest = new GameResult.Player(0L, "Guest", 1, 3, 12, 300, false);

            server.send(new HubPacket(Opcodes.Hub.RESULT, new GameResult("rac3-pal-1", "Siege", 2, 41, 600, 0, List.of(winner, guest)).serialize()));
            server.send(new HubPacket(Opcodes.Hub.RESULT, new GameResult("rac3-pal-1", "Siege", 2, 41, 600, 1, List.of(winner)).serialize()));

            OutputBuffer fields = new OutputBuffer();

            fields.writeLong(ratchet.id());

            InputBuffer totals = ask(ratchet.peer(), Opcodes.Stats.TOTALS, fields);

            Assertions.assertEquals(ratchet.id(), totals.readLong());
            Assertions.assertEquals("rac3-pal-1", totals.readString());
            Assertions.assertEquals(2, totals.readInt());
            Assertions.assertEquals(2, totals.readInt());
            Assertions.assertEquals(24, totals.readInt());
            Assertions.assertEquals(6, totals.readInt());
            Assertions.assertEquals(2400L, totals.readLong());
        }

        @Test
        @DisplayName("Writing stats needs a login, and a bad sort index closes")
        public void testRefused() {
            Peer anonymous = guest("Clank");
            OutputBuffer write = new OutputBuffer();

            write.writeString("rac3-pal-1");
            write.writeInt(0);
            write.writeUnsignedByte(1);
            write.writeInt(5);
            anonymous.send(new StatsPacket(Opcodes.Stats.LADDER_WRITE, write));

            Assertions.assertEquals(Opcodes.Error.NOT_LOGGED_IN, anonymous.receiveError());

            OutputBuffer page = new OutputBuffer();

            page.writeString("rac3-pal-1");
            page.writeInt(0);
            page.writeUnsignedByte(Constants.MAX_LADDER_STATS);
            page.writeUnsignedByte(1);
            page.writeInt(1);
            page.writeUnsignedByte(10);
            anonymous.send(new StatsPacket(Opcodes.Stats.LADDER_PAGE, page));

            Assertions.assertFalse(anonymous.channel.isOpen());
        }

        @Test
        @DisplayName("The ladder sorts by the asked value, ties sharing a position")
        public void testLadder() {
            Player ratchet = player("Ratchet");
            Player clank = player("Clank");
            Player qwark = player("Qwark");

            writeLadder(ratchet, 1, 50);
            writeLadder(clank, 2, 80);
            writeLadder(qwark, 3, 50);

            OutputBuffer request = new OutputBuffer();

            request.writeInt(0);
            request.writeUnsignedByte(1);
            request.writeUnsignedByte(1);
            request.writeInt(1);
            request.writeUnsignedByte(10);

            InputBuffer page = ask(ratchet.peer(), Opcodes.Stats.LADDER_PAGE, request);

            Assertions.assertEquals(3, page.readInt());
            Assertions.assertEquals(3, page.readUnsignedByte());

            int[] positions = new int[3];
            long[] ids = new long[3];

            for (int i = 0; i < 3; i++) {
                positions[i] = page.readInt();
                ids[i] = page.readLong();
                page.readString();

                int count = page.readUnsignedByte();

                for (int j = 0; j < count; j++) page.readInt();
            }

            Assertions.assertArrayEquals(new int[] { 1, 2, 2 }, positions);
            Assertions.assertArrayEquals(new long[] { clank.id(), ratchet.id(), qwark.id() }, ids);

            OutputBuffer where = new OutputBuffer();

            where.writeLong(qwark.id());
            where.writeInt(0);
            where.writeUnsignedByte(1);
            where.writeUnsignedByte(1);

            InputBuffer position = ask(ratchet.peer(), Opcodes.Stats.LADDER_POSITION, where);

            Assertions.assertEquals(qwark.id(), position.readLong());
            Assertions.assertEquals(2, position.readInt());
            Assertions.assertEquals(3, position.readInt());
        }

        @Test
        @DisplayName("Ladder values and career bytes read back, and none read as empty")
        public void testReadBack() {
            Player ratchet = player("Ratchet");

            writeLadder(ratchet, 7, 8, 9);

            OutputBuffer read = new OutputBuffer();

            read.writeLong(ratchet.id());
            read.writeInt(0);

            InputBuffer values = ask(ratchet.peer(), Opcodes.Stats.LADDER_READ, read);

            Assertions.assertEquals(ratchet.id(), values.readLong());
            Assertions.assertEquals(0, values.readInt());
            Assertions.assertEquals(3, values.readUnsignedByte());
            Assertions.assertEquals(7, values.readInt());

            OutputBuffer career = new OutputBuffer();

            career.writeBytes(HexFormat.of().parseHex("0102ff"));
            ask(ratchet.peer(), Opcodes.Stats.CAREER_WRITE, career);

            OutputBuffer readCareer = new OutputBuffer();

            readCareer.writeLong(ratchet.id());

            InputBuffer bytes = ask(ratchet.peer(), Opcodes.Stats.CAREER_READ, readCareer);

            Assertions.assertEquals(ratchet.id(), bytes.readLong());
            Assertions.assertArrayEquals(HexFormat.of().parseHex("0102ff"), bytes.readRemainingBytes());

            OutputBuffer unknown = new OutputBuffer();

            unknown.writeLong(999L);
            unknown.writeInt(0);

            InputBuffer none = ask(ratchet.peer(), Opcodes.Stats.LADDER_READ, unknown);

            none.readLong();
            none.readInt();
            Assertions.assertEquals(0, none.readUnsignedByte());
        }
    }

    @Nested
    @DisplayName("Limit Tests")
    class LimitTests {

        @Test
        @DisplayName("A client past its rate is told to try later, not left waiting")
        public void testTryLater() {
            Peer guest = guest("Clank");

            for (int i = 0; i < Constants.REQUEST_BURST; i++) {
                guest.send(new RegionsPacket("rac3-pal-1"));
                guest.receive(Packets.REGIONS);
            }

            guest.send(new RegionsPacket("rac3-pal-1"));

            Assertions.assertEquals(Opcodes.Error.TRY_LATER, guest.receiveError());
            Assertions.assertTrue(guest.channel.isOpen());
        }

        @Test
        @DisplayName("A logged-in client gets the larger budget for the game's stats burst")
        public void testLoggedInBudget() {
            Peer player = guest("Ratchet");

            login(player, "Ratchet", "lombax");

            for (int i = 0; i < Constants.ACCOUNT_REQUEST_BURST; i++) {
                player.send(new RegionsPacket("rac3-pal-1"));
                player.receive(Packets.REGIONS);
            }

            player.assertSilent();
        }
    }

    /**
     * A logged-in test client.
     * @param peer Its connection.
     * @param id Its account id.
     */

    private record Player(Peer peer, long id) {}
}
