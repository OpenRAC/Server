package com.openrac.hub;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openrac.hub.accounts.AccountController;
import com.openrac.hub.accounts.AccountStore;
import com.openrac.hub.accounts.MemoryAccountStore;
import com.openrac.hub.accounts.PostgresAccountStore;
import com.openrac.hub.database.Database;
import com.openrac.hub.database.Sql;
import com.openrac.hub.stats.MemoryStatsStore;
import com.openrac.hub.stats.PostgresStatsStore;
import com.openrac.hub.stats.StatsController;
import com.openrac.hub.stats.StatsStore;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.GameResult;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.model.OutputBuffer;
import com.openrac.net.packets.out.AccountPacket;
import com.openrac.net.packets.out.HubPacket;
import com.openrac.net.packets.out.StatsPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs the PostgreSQL stores against a real database: TEST_DATABASE_URL, TEST_DATABASE_USER and
 * TEST_DATABASE_PASSWORD in the environment. Skipped when they are unset or nothing answers.
 */

public class PostgresStoreTests {
    private static Database database;
    private static PostgresAccountStore store;
    private static PostgresStatsStore stats;

    /**
     * Opens the test database and migrates it, or skips every test.
     */

    @BeforeAll
    public static void open() {
        String url = System.getenv("TEST_DATABASE_URL");

        Assumptions.assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL is not set.");

        try {
            database = new Database(url, System.getenv().getOrDefault("TEST_DATABASE_USER", "openrac"),
                    System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""), 2);
        } catch (RuntimeException exception) {
            Assumptions.abort("No database at " + url + ": " + exception.getMessage());
        }

        Sql sql = new Sql(database.getPool());

        store = new PostgresAccountStore(sql);
        stats = new PostgresStatsStore(sql);
    }

    /**
     * Closes the pool, if one opened.
     */

    @AfterAll
    public static void close() {
        // A skipped run opened nothing.
        if (database != null) database.close();
    }

    @Nested
    @DisplayName("Postgres Store Tests")
    class PostgresTests extends StoreContract {

        /**
         * @return The PostgreSQL account store.
         */

        @Override
        protected AccountStore getAccounts() {
            return store;
        }

        /**
         * @return The PostgreSQL stats store.
         */

        @Override
        protected StatsStore getStats() {
            return stats;
        }
    }

    @Nested
    @DisplayName("Postgres Login Tests")
    class LoginTests {
        private final List<Peer> peers = new ArrayList<>();

        /**
         * Points the hub at the database, worked on the calling thread.
         */

        @BeforeEach
        public void useDatabase() {
            AccountController.setStore(store);
            StatsController.setStore(stats);
            Background.setExecutor(Runnable::run);
        }

        /**
         * Closes every peer and puts the memory stores back.
         */

        @AfterEach
        public void useMemory() {
            for (Peer peer : peers) peer.channel.close();

            AccountController.setStore(new MemoryAccountStore());
            StatsController.setStore(new MemoryStatsStore());
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
         * Says hello and logs in.
         * @param name The account name.
         * @param password The password.
         * @return The LOGGED_IN payload after its opcode.
         */

        private InputBuffer login(String name, String password) {
            Peer peer = connect();

            peer.hello(name);
            peer.send(new AccountPacket(name, password));

            InputBuffer payload = peer.receive(Packets.ACCOUNT);

            Assertions.assertEquals(Opcodes.Account.LOGGED_IN, Opcodes.Account.get(payload.readUnsignedByte()));

            return payload;
        }

        @Test
        @DisplayName("A first login registers in the database, and a game server verifies its token")
        public void testLoginAndVerify() {
            String name = "D" + UUID.randomUUID().toString().substring(0, 8);
            InputBuffer first = login(name, "lombax");
            long id = first.readLong();

            first.readString();

            String token = first.readString();

            Assertions.assertEquals(1, first.readUnsignedByte());
            Assertions.assertEquals(id, store.find(name).id());

            InputBuffer again = login(name.toLowerCase(), "lombax");

            Assertions.assertEquals(id, again.readLong());

            Peer server = connect();

            server.register("US", 7800);
            server.send(new HubPacket(7, token));

            InputBuffer verified = server.receiveHub(Opcodes.Hub.VERIFIED);

            Assertions.assertEquals(7, verified.readInt());
            Assertions.assertEquals(id, verified.readLong());
            Assertions.assertEquals(name, verified.readString());
        }

        @Test
        @DisplayName("A wrong password is refused against the stored hash")
        public void testWrongPassword() {
            String name = "W" + UUID.randomUUID().toString().substring(0, 8);

            login(name, "lombax");

            Peer thief = connect();

            thief.hello("Nefarious");
            thief.send(new AccountPacket(name, "robots"));

            Assertions.assertEquals(Opcodes.Error.WRONG_PASSWORD, thief.receiveError());
        }

        @Test
        @DisplayName("A game result a server reports reaches the database's totals")
        public void testResult() {
            String name = "R" + UUID.randomUUID().toString().substring(0, 8);
            Peer player = connect();

            player.hello(name);
            player.send(new AccountPacket(name, "lombax"));

            InputBuffer loggedIn = player.receive(Packets.ACCOUNT);

            loggedIn.readUnsignedByte();

            long id = loggedIn.readLong();
            Peer server = connect();

            server.register("DE", 7801);

            GameResult.Player winner = new GameResult.Player(id, name, 0, 7, 1, 700, true);

            server.send(new HubPacket(Opcodes.Hub.RESULT, new GameResult("rac3-pal-1", "Siege", 2, 41, 600, 0, List.of(winner)).serialize()));

            OutputBuffer request = new OutputBuffer();

            request.writeString("rac3-pal-1");
            request.writeLong(id);
            player.send(new StatsPacket(Opcodes.Stats.TOTALS, request));

            InputBuffer totals = player.receive(Packets.STATS);

            Assertions.assertEquals(Opcodes.Stats.TOTALS, Opcodes.Stats.get(totals.readUnsignedByte()));
            Assertions.assertEquals(id, totals.readLong());
            Assertions.assertEquals("rac3-pal-1", totals.readString());
            Assertions.assertEquals(new StatsStore.Totals(1, 1, 7, 1, 700), stats.getTotals(id, "rac3-pal-1"));
        }
    }
}
