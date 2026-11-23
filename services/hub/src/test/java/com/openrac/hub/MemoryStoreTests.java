package com.openrac.hub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import com.openrac.hub.accounts.AccountStore;
import com.openrac.hub.accounts.MemoryAccountStore;
import com.openrac.hub.stats.MemoryStatsStore;
import com.openrac.hub.stats.StatsStore;

/**
 * Runs the memory stores through the same contract as PostgreSQL's, so a hub without a database
 * behaves as one with it does.
 */

public class MemoryStoreTests {

    @Nested
    @DisplayName("Memory Store Tests")
    class MemoryTests extends StoreContract {
        private AccountStore accounts;
        private StatsStore stats;

        /**
         * Gives each test empty stores.
         */

        @BeforeEach
        public void createStores() {
            accounts = new MemoryAccountStore();
            stats = new MemoryStatsStore();
        }

        /**
         * @return The memory account store.
         */

        @Override
        protected AccountStore getAccounts() {
            return accounts;
        }

        /**
         * @return The memory stats store.
         */

        @Override
        protected StatsStore getStats() {
            return stats;
        }
    }
}
