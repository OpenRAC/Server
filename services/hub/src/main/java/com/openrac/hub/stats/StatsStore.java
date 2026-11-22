package com.openrac.hub.stats;

import com.openrac.hub.accounts.Account;
import com.openrac.net.packets.model.GameResult;

import java.util.List;

/**
 * Where the hub keeps finished games, each account's totals, and the game's own ladder and career
 * stats. Every call may block, so the hub only makes them off its event loops. A ladder sorts by
 * one of its values; ties share a position, and an account without that value counts it as 0.
 */

public interface StatsStore {
    /**
     * Stores a finished game and adds it to its players' totals. Guests (account 0) only get a line.
     * @param region The region of the server that reported it.
     * @param server The name of that server.
     * @param result The game.
     */

    void recordGame(String region, String server, GameResult result);

    /**
     * @param accountId An account.
     * @param family A game family.
     * @return The account's totals in that family, all 0 when it has played none.
     */

    Totals getTotals(long accountId, String family);

    /**
     * Replaces an account's ladder values.
     * @param account The account writing its own values.
     * @param family The game family.
     * @param type The game's ladder type.
     * @param stats The values, at most 100.
     */

    void writeLadder(Account account, String family, int type, int[] stats);

    /**
     * @param accountId An account.
     * @param family The game family.
     * @param type The game's ladder type.
     * @return Its ladder values, or null when it has written none.
     */

    int[] readLadder(long accountId, String family, int type);

    /**
     * @param family The game family.
     * @param type The game's ladder type.
     * @param index The value to sort by, from 0.
     * @param descending Whether the highest value comes first.
     * @param start The first position on the page, from 1.
     * @param count The most rows on the page.
     * @return How many accounts the ladder holds, and the page's rows.
     */

    LadderPage getLadderPage(String family, int type, int index, boolean descending, int start, int count);

    /**
     * @param accountId An account.
     * @param family The game family.
     * @param type The game's ladder type.
     * @param index The value to sort by, from 0.
     * @param descending Whether the highest value comes first.
     * @return Its position, 0 when it is not on the ladder, and how many accounts are.
     */

    Position getLadderPosition(long accountId, String family, int type, int index, boolean descending);

    /**
     * Replaces an account's career bytes.
     * @param account The account writing its own bytes.
     * @param family The game family.
     * @param stats The bytes, at most 256.
     */

    void writeCareer(Account account, String family, byte[] stats);

    /**
     * @param accountId An account.
     * @param family The game family.
     * @return Its career bytes, or null when it has written none.
     */

    byte[] readCareer(long accountId, String family);

    /**
     * An account's totals in one family.
     * @param games Games it finished.
     * @param wins Games it was on the winning side of.
     * @param kills Players it killed.
     * @param deaths Times it died.
     * @param score Its scores added up.
     */

    record Totals(int games, int wins, int kills, int deaths, long score) {}

    /**
     * One account on a ladder page.
     * @param position Its position, from 1.
     * @param accountId The account.
     * @param name The account's name.
     * @param stats Its ladder values.
     */

    record LadderRow(int position, long accountId, String name, int[] stats) {}

    /**
     * A page of a ladder.
     * @param total How many accounts the ladder holds.
     * @param rows The page's rows, in order.
     */

    record LadderPage(int total, List<LadderRow> rows) {}

    /**
     * Where an account stands.
     * @param position Its position from 1, or 0 when it is not on the ladder.
     * @param total How many accounts the ladder holds.
     */

    record Position(int position, int total) {}
}
