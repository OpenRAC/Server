package com.openrac.hub;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openrac.hub.accounts.AccountController;
import com.openrac.hub.accounts.MemoryAccountStore;
import com.openrac.hub.servers.ServerController;
import com.openrac.net.NetworkConstants;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.AccountPacket;
import com.openrac.net.packets.out.HubPacket;
import com.openrac.net.packets.out.PartyPacket;
import com.openrac.net.packets.out.RegionsPacket;
import com.openrac.net.packets.out.RegisterPacket;
import com.openrac.net.packets.out.ServersPacket;
import com.openrac.utils.Clock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the hub on its real pipeline: game servers registering and reporting, the list clients read
 * and watch, and account logins.
 */

public class HubFlowTests {
    private final List<Peer> peers = new ArrayList<>();

    /**
     * Gives each test empty accounts, worked on the calling thread.
     */

    @BeforeEach
    public void resetAccounts() {
        AccountController.setStore(new MemoryAccountStore());
        AccountController.setExecutor(Runnable::run);
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

            server.register("Polaris", 7800);

            Assertions.assertEquals(7800, regions(player).get("Polaris"));

            server.channel.close();

            Assertions.assertNull(regions(player).get("Polaris"));
        }

        @Test
        @DisplayName("A server is closed for a second registration or a bad port")
        public void testInvalid() {
            Peer twice = connect();

            twice.register("Polaris", 7800);
            twice.send(new RegisterPacket("", "Polaris", "", 7800, 0, "box"));

            Assertions.assertFalse(twice.channel.isOpen());

            Peer badPort = connect();

            badPort.send(new RegisterPacket("", "Veldin", "", 0, 0, "box"));

            Assertions.assertFalse(badPort.channel.isOpen());
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
            int id = server.register("Polaris", 7800);
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
            int id = server.register("Polaris", 7800);
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

            server.register("Polaris", 7800);
            server.send(new HubPacket(Opcodes.Hub.CLOSING));

            Assertions.assertNull(regions(guest("Clank")).get("Polaris"));
        }
    }

    @Nested
    @DisplayName("Watch Tests")
    class WatchTests {

        @Test
        @DisplayName("A watcher gets every server, then each new one")
        public void testWatch() {
            connect().register("Polaris", 7800);

            Peer watcher = guest("Clank");

            watcher.send(new ServersPacket("rac3-pal-1"));

            InputBuffer added = watcher.receiveServers(Opcodes.Servers.ADDED);

            added.readInt();
            Assertions.assertEquals("Polaris", added.readString());
            Assertions.assertEquals("Polaris box", added.readString());
            Assertions.assertEquals("", added.readString());
            Assertions.assertEquals(7800, added.readInt());

            connect().register("Veldin", 7801);

            InputBuffer next = watcher.receiveServers(Opcodes.Servers.ADDED);

            next.readInt();
            Assertions.assertEquals("Veldin", next.readString());
        }
    }

    @Nested
    @DisplayName("Account Tests")
    class AccountTests {

        /**
         * Logs in and reads the answer.
         * @param guest The client logging in.
         * @param name The account name.
         * @param password The password.
         * @return The LOGGED_IN payload after its opcode.
         */

        private InputBuffer login(Peer guest, String name, String password) {
            guest.send(new AccountPacket(name, password));

            InputBuffer payload = guest.receive(Packets.ACCOUNT);

            Assertions.assertEquals(Opcodes.Account.LOGGED_IN, Opcodes.Account.get(payload.readUnsignedByte()));

            return payload;
        }

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

            server.register("Polaris", 7800);
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
}
