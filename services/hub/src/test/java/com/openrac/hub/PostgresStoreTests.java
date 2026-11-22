package com.openrac.hub;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openrac.hub.accounts.Account;
import com.openrac.hub.accounts.PostgresAccountStore;
import com.openrac.hub.database.Database;
import com.openrac.hub.database.Sql;
import com.openrac.hub.stats.PostgresStatsStore;
import com.openrac.hub.stats.StatsStore;
import com.openrac.net.packets.model.GameResult;

import java.time.Duration;
import java.time.Instant;
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
    class PostgresTests {

        @Test
        @DisplayName("A name registers once, in any case, and is found again")
        public void testAccounts() {
            String name = "T" + UUID.randomUUID().toString().substring(0, 8);
            Account created = store.create(name, "hash");

            Assertions.assertNotNull(created);
            Assertions.assertNull(store.create(name.toLowerCase(), "other"));
            Assertions.assertEquals(created.id(), store.find(name.toUpperCase()).id());

            store.touch(created.id());
        }

        @Test
        @DisplayName("A session is found until it expires or ends")
        public void testSessions() {
            Account account = store.create("S" + UUID.randomUUID().toString().substring(0, 8), "hash");
            String token = UUID.randomUUID().toString();
            Instant now = Instant.now();

            store.addSession(token, account.id(), now.plus(Duration.ofMinutes(5)));

            Assertions.assertEquals(account.id(), store.findSession(token, now).id());
            Assertions.assertNull(store.findSession(token, now.plus(Duration.ofMinutes(10))));

            store.removeSession(token);

            Assertions.assertNull(store.findSession(token, now));

            store.removeExpired(now);
        }

        @Test
        @DisplayName("A reported game adds to totals, and guests only get a line")
        public void testGames() {
            Account account = store.create("G" + UUID.randomUUID().toString().substring(0, 8), "hash");
            GameResult.Player player = new GameResult.Player(account.id(), account.name(), 0, 4, 2, 100, true);
            GameResult.Player guest = new GameResult.Player(0L, "Guest", 1, 2, 4, 50, false);

            stats.recordGame("Europe", "Box", new GameResult("rac3-pal-1", "Siege", 2, 41, 300, 0, List.of(player, guest)));
            stats.recordGame("Europe", "Box", new GameResult("rac3-pal-1", "Siege", 2, 41, 300, 1, List.of(player)));

            Assertions.assertEquals(new StatsStore.Totals(2, 2, 8, 4, 200), stats.getTotals(account.id(), "rac3-pal-1"));
            Assertions.assertEquals(new StatsStore.Totals(0, 0, 0, 0, 0), stats.getTotals(account.id(), "rac1-60-1"));
        }

        @Test
        @DisplayName("A ladder sorts by the asked value and finds an account's position")
        public void testLadder() {
            String family = "test-" + UUID.randomUUID().toString().substring(0, 8);
            Account high = store.create("H" + UUID.randomUUID().toString().substring(0, 8), "hash");
            Account low = store.create("L" + UUID.randomUUID().toString().substring(0, 8), "hash");

            stats.writeLadder(high, family, 0, new int[] { 1, 90 });
            stats.writeLadder(low, family, 0, new int[] { 2, 10 });
            stats.writeLadder(low, family, 0, new int[] { 2, 20 });

            StatsStore.LadderPage page = stats.getLadderPage(family, 0, 1, true, 1, 10);

            Assertions.assertEquals(2, page.total());
            Assertions.assertEquals(high.id(), page.rows().getFirst().accountId());
            Assertions.assertEquals(20, page.rows().get(1).stats()[1]);
            Assertions.assertEquals(new StatsStore.Position(2, 2), stats.getLadderPosition(low.id(), family, 0, 1, true));
            Assertions.assertEquals(new StatsStore.Position(1, 2), stats.getLadderPosition(low.id(), family, 0, 1, false));

            stats.writeCareer(high, family, new byte[] { 1, 2, 3 });

            Assertions.assertArrayEquals(new byte[] { 1, 2, 3 }, stats.readCareer(high.id(), family));
            Assertions.assertNull(stats.readCareer(low.id(), family));
        }
    }
}
