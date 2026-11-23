package com.openrac.hub.stats;

import com.openrac.hub.accounts.Account;
import com.openrac.net.packets.model.GameResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stats kept in memory, beside the memory accounts: gone when the hub stops. Games are added to
 * the totals but not kept themselves. The tests use it too.
 */

public class MemoryStatsStore implements StatsStore {
    private final Map<Key, Totals> totals = new HashMap<>();
    private final Map<Key, Held> ladders = new HashMap<>();
    private final Map<Key, byte[]> careers = new HashMap<>();

    /**
     * Adds a finished game to its players' totals. Guests (account 0) are left out.
     * @param region The region of the server that reported it.
     * @param server The name of that server.
     * @param result The game.
     */

    @Override
    public synchronized void recordGame(String region, String server, GameResult result) {
        for (GameResult.Player player : result.players()) {
            // A guest has no totals to add to.
            if (player.accountId() == 0) continue;

            Key key = new Key(player.accountId(), result.family(), 0);
            Totals old = totals.getOrDefault(key, new Totals(0, 0, 0, 0, 0));

            // A game on the winning side counts as one win.
            totals.put(key, new Totals(old.games() + 1, old.wins() + (player.won() ? 1 : 0), old.kills() + player.kills(),
                    old.deaths() + player.deaths(), old.score() + player.score()));
        }
    }

    /**
     * @param accountId An account.
     * @param family A game family.
     * @return The account's totals in that family, all 0 when it has played none.
     */

    @Override
    public synchronized Totals getTotals(long accountId, String family) {
        return totals.getOrDefault(new Key(accountId, family, 0), new Totals(0, 0, 0, 0, 0));
    }

    /**
     * Replaces an account's ladder values.
     * @param account The account writing its own values.
     * @param family The game family.
     * @param type The game's ladder type.
     * @param stats The values, at most 100.
     */

    @Override
    public synchronized void writeLadder(Account account, String family, int type, int[] stats) {
        ladders.put(new Key(account.id(), family, type), new Held(account.name(), stats.clone()));
    }

    /**
     * @param accountId An account.
     * @param family The game family.
     * @param type The game's ladder type.
     * @return Its ladder values, or null when it has written none.
     */

    @Override
    public synchronized int[] readLadder(long accountId, String family, int type) {
        Held held = ladders.get(new Key(accountId, family, type));

        // An account that never wrote has no row.
        return held == null ? null : held.stats().clone();
    }

    /**
     * @param family The game family.
     * @param type The game's ladder type.
     * @param index The value to sort by, from 0.
     * @param descending Whether the highest value comes first.
     * @param start The first position on the page, from 1.
     * @param count The most rows on the page.
     * @return How many accounts the ladder holds, and the page's rows.
     */

    @Override
    public synchronized LadderPage getLadderPage(String family, int type, int index, boolean descending, int start, int count) {
        List<LadderRow> ranked = rank(family, type, index, descending);
        int from = Math.min(ranked.size(), start - 1);

        return new LadderPage(ranked.size(), List.copyOf(ranked.subList(from, Math.min(ranked.size(), from + count))));
    }

    /**
     * @param accountId An account.
     * @param family The game family.
     * @param type The game's ladder type.
     * @param index The value to sort by, from 0.
     * @param descending Whether the highest value comes first.
     * @return Its position, 0 when it is not on the ladder, and how many accounts are.
     */

    @Override
    public synchronized Position getLadderPosition(long accountId, String family, int type, int index, boolean descending) {
        List<LadderRow> ranked = rank(family, type, index, descending);

        for (LadderRow row : ranked) {
            // Found the account, so its position is known.
            if (row.accountId() == accountId) return new Position(row.position(), ranked.size());
        }

        return new Position(0, ranked.size());
    }

    /**
     * Replaces an account's career bytes.
     * @param account The account writing its own bytes.
     * @param family The game family.
     * @param stats The bytes, at most 256.
     */

    @Override
    public synchronized void writeCareer(Account account, String family, byte[] stats) {
        careers.put(new Key(account.id(), family, 0), stats.clone());
    }

    /**
     * @param accountId An account.
     * @param family The game family.
     * @return Its career bytes, or null when it has written none.
     */

    @Override
    public synchronized byte[] readCareer(long accountId, String family) {
        byte[] stats = careers.get(new Key(accountId, family, 0));

        // An account that never wrote has no bytes.
        return stats == null ? null : stats.clone();
    }

    /**
     * Sorts one ladder by a value and gives each row its position, ties sharing one.
     * @param family The game family.
     * @param type The game's ladder type.
     * @param index The value to sort by.
     * @param descending Whether the highest value comes first.
     * @return Every row of the ladder, in order.
     */

    private List<LadderRow> rank(String family, int type, int index, boolean descending) {
        List<Map.Entry<Key, Held>> entries = new ArrayList<>();

        for (Map.Entry<Key, Held> entry : ladders.entrySet()) {
            // Only this family's ladder of this type.
            if (entry.getKey().family().equals(family) && entry.getKey().type() == type) entries.add(entry);
        }

        Comparator<Map.Entry<Key, Held>> byValue = Comparator.comparingInt(entry -> valueAt(entry.getValue().stats(), index));

        // The asked direction, then ties by account id as in the database.
        entries.sort((descending ? byValue.reversed() : byValue).thenComparingLong(entry -> entry.getKey().accountId()));

        List<LadderRow> rows = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            Held held = entries.get(i).getValue();
            int value = valueAt(held.stats(), index);

            // A tie keeps the position of the row above it.
            int position = i > 0 && valueAt(entries.get(i - 1).getValue().stats(), index) == value ? rows.get(i - 1).position() : i + 1;

            rows.add(new LadderRow(position, entries.get(i).getKey().accountId(), held.name(), held.stats().clone()));
        }

        return rows;
    }

    /**
     * @param stats A row's values.
     * @param index The value wanted.
     * @return The value, or 0 past the end of the row.
     */

    private static int valueAt(int[] stats, int index) {
        // A short row counts its missing values as 0.
        return index < stats.length ? stats[index] : 0;
    }

    /**
     * Whose row, in which family, of which ladder type (0 for totals and careers).
     * @param accountId The account.
     * @param family The game family.
     * @param type The ladder type.
     */

    private record Key(long accountId, String family, int type) {}

    /**
     * A ladder row as memory holds it.
     * @param name The account's name, for the pages.
     * @param stats Its values.
     */

    private record Held(String name, int[] stats) {}
}
