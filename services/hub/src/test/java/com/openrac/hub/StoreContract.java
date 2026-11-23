package com.openrac.hub;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.openrac.hub.accounts.Account;
import com.openrac.hub.accounts.AccountStore;
import com.openrac.hub.stats.StatsStore;
import com.openrac.net.packets.model.GameResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What every account and stats store must do alike, so the memory stores behave as PostgreSQL's
 * do. Each kind of store runs these tests from a nested class that hands its stores over.
 */

public abstract class StoreContract {
    /**
     * @return The account store under test.
     */

    protected abstract AccountStore getAccounts();

    /**
     * @return The stats store under test, beside the same accounts.
     */

    protected abstract StatsStore getStats();

    /**
     * @param prefix The first letter of the name.
     * @return A name no earlier run has registered.
     */

    private static String newName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("A name registers once, in any case, and is found again")
    public void testAccounts() {
        String name = newName("T");
        Account created = getAccounts().create(name, "hash");

        Assertions.assertNotNull(created);
        Assertions.assertNull(getAccounts().create(name.toLowerCase(), "other"));
        Assertions.assertEquals(created.id(), getAccounts().find(name.toUpperCase()).id());
        Assertions.assertEquals("hash", getAccounts().find(name).passwordHash());
        Assertions.assertNull(getAccounts().find(newName("N")));

        getAccounts().touch(created.id());
    }

    @Test
    @DisplayName("A session is found until it expires or ends")
    public void testSessions() {
        Account account = getAccounts().create(newName("S"), "hash");
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();

        getAccounts().addSession(token, account.id(), now.plus(Duration.ofMinutes(5)));

        Assertions.assertEquals(account.id(), getAccounts().findSession(token, now).id());
        Assertions.assertNull(getAccounts().findSession(token, now.plus(Duration.ofMinutes(10))));

        getAccounts().removeSession(token);

        Assertions.assertNull(getAccounts().findSession(token, now));

        getAccounts().removeExpired(now);
    }

    @Test
    @DisplayName("Expired sessions are forgotten and live ones kept")
    public void testRemoveExpired() {
        Account account = getAccounts().create(newName("E"), "hash");
        String expired = UUID.randomUUID().toString();
        String live = UUID.randomUUID().toString();
        Instant now = Instant.now();

        getAccounts().addSession(expired, account.id(), now.minus(Duration.ofMinutes(1)));
        getAccounts().addSession(live, account.id(), now.plus(Duration.ofMinutes(5)));
        getAccounts().removeExpired(now);

        Assertions.assertNull(getAccounts().findSession(expired, now.minus(Duration.ofMinutes(2))));
        Assertions.assertEquals(account.id(), getAccounts().findSession(live, now).id());
    }

    @Test
    @DisplayName("A reported game adds to totals, and guests only get a line")
    public void testGames() {
        Account account = getAccounts().create(newName("G"), "hash");
        GameResult.Player player = new GameResult.Player(account.id(), account.name(), 0, 4, 2, 100, true);
        GameResult.Player guest = new GameResult.Player(0L, "Guest", 1, 2, 4, 50, false);

        getStats().recordGame("Europe", "Box", new GameResult("rac3-pal-1", "Siege", 2, 41, 300, 0, List.of(player, guest)));
        getStats().recordGame("Europe", "Box", new GameResult("rac3-pal-1", "Siege", 2, 41, 300, 1, List.of(player)));

        Assertions.assertEquals(new StatsStore.Totals(2, 2, 8, 4, 200), getStats().getTotals(account.id(), "rac3-pal-1"));
        Assertions.assertEquals(new StatsStore.Totals(0, 0, 0, 0, 0), getStats().getTotals(account.id(), "rac1-60-1"));
        Assertions.assertEquals(new StatsStore.Totals(0, 0, 0, 0, 0), getStats().getTotals(0L, "rac3-pal-1"));
    }

    @Test
    @DisplayName("A ladder sorts by the asked value and finds an account's position")
    public void testLadder() {
        String family = "test-" + UUID.randomUUID().toString().substring(0, 8);
        Account high = getAccounts().create(newName("H"), "hash");
        Account low = getAccounts().create(newName("L"), "hash");

        getStats().writeLadder(high, family, 0, new int[] { 1, 90 });
        getStats().writeLadder(low, family, 0, new int[] { 2, 10 });
        getStats().writeLadder(low, family, 0, new int[] { 2, 20 });

        StatsStore.LadderPage page = getStats().getLadderPage(family, 0, 1, true, 1, 10);

        Assertions.assertEquals(2, page.total());
        Assertions.assertEquals(high.id(), page.rows().getFirst().accountId());
        Assertions.assertEquals(high.name(), page.rows().getFirst().name());
        Assertions.assertEquals(20, page.rows().get(1).stats()[1]);
        Assertions.assertEquals(new StatsStore.Position(2, 2), getStats().getLadderPosition(low.id(), family, 0, 1, true));
        Assertions.assertEquals(new StatsStore.Position(1, 2), getStats().getLadderPosition(low.id(), family, 0, 1, false));
        Assertions.assertArrayEquals(new int[] { 2, 20 }, getStats().readLadder(low.id(), family, 0));
        Assertions.assertEquals(0, getStats().getLadderPage(family, 1, 1, true, 1, 10).total());
    }

    @Test
    @DisplayName("A page starts at its position and holds at most its count")
    public void testLadderPaging() {
        String family = "page-" + UUID.randomUUID().toString().substring(0, 8);

        for (int score = 1; score <= 5; score++) getStats().writeLadder(getAccounts().create(newName("P"), "hash"), family, 0, new int[] { score });

        StatsStore.LadderPage page = getStats().getLadderPage(family, 0, 0, true, 2, 2);

        Assertions.assertEquals(5, page.total());
        Assertions.assertEquals(2, page.rows().size());
        Assertions.assertEquals(2, page.rows().getFirst().position());
        Assertions.assertEquals(4, page.rows().getFirst().stats()[0]);
        Assertions.assertEquals(3, page.rows().get(1).stats()[0]);
    }

    @Test
    @DisplayName("Career bytes read back, are replaced whole, and read as none before a write")
    public void testCareer() {
        String family = "career-" + UUID.randomUUID().toString().substring(0, 8);
        Account account = getAccounts().create(newName("C"), "hash");

        Assertions.assertNull(getStats().readCareer(account.id(), family));

        getStats().writeCareer(account, family, new byte[] { 1, 2, 3 });
        getStats().writeCareer(account, family, new byte[] { 4, 5 });

        Assertions.assertArrayEquals(new byte[] { 4, 5 }, getStats().readCareer(account.id(), family));
        Assertions.assertNull(getStats().readCareer(account.id(), "other-" + family));
    }
}
