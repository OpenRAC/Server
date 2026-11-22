package com.openrac.hub;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openrac.hub.accounts.Account;
import com.openrac.hub.accounts.Database;
import com.openrac.hub.accounts.PostgresAccountStore;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Runs the PostgreSQL store against a real database: TEST_DATABASE_URL, TEST_DATABASE_USER and
 * TEST_DATABASE_PASSWORD in the environment. Skipped when they are unset or nothing answers.
 */

public class PostgresStoreTests {
    private static Database database;
    private static PostgresAccountStore store;

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

        store = new PostgresAccountStore(database.getPool());
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
    }
}
