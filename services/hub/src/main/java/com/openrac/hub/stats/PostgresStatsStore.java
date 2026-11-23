package com.openrac.hub.stats;

import com.openrac.hub.accounts.Account;
import com.openrac.hub.database.Sql;
import com.openrac.net.packets.model.GameResult;

import java.sql.Array;
import java.sql.SQLException;
import java.util.List;

/**
 * Games, totals, ladders and careers in PostgreSQL, in the tables the migrations create. A ladder
 * is sorted in the query by one element of its stats array (1-based in SQL, so index + 1).
 */

public class PostgresStatsStore implements StatsStore {
    // An account without the value counts it as 0.
    private static final String VALUE = "COALESCE(l.stats[?], 0)";

    private final Sql sql;

    /**
     * Creates the store.
     * @param sql The runner on the database's pool.
     */

    public PostgresStatsStore(Sql sql) {
        this.sql = sql;
    }

    /**
     * Stores a finished game and adds it to its players' totals, all in one transaction. Guests
     * (account 0) and accounts that no longer exist only get a line.
     * @param region The region of the server that reported it.
     * @param server The name of that server.
     * @param result The game.
     */

    @Override
    public void recordGame(String region, String server, GameResult result) {
        sql.transaction(game -> {
            String insertGame = "INSERT INTO games (family, region, server, name, mode, map, duration_seconds, winning_team) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
            long gameId = game.queryOne(insertGame, row -> row.getLong(1), result.family(), region, server, result.name(), result.mode(), result.map(),
                    result.duration(), result.winningTeam());

            for (int slot = 0; slot < result.players().size(); slot++) {
                GameResult.Player player = result.players().get(slot);

                game.update("INSERT INTO game_players (game_id, slot, account_id, name, team, kills, deaths, score, won) "
                        + "VALUES (?, ?, (SELECT id FROM accounts WHERE id = ?), ?, ?, ?, ?, ?, ?)", gameId, slot, player.accountId(), player.name(),
                        player.team(), player.kills(), player.deaths(), player.score(), player.won());

                // Selecting from accounts skips guests and deleted accounts.
                game.update("INSERT INTO player_stats (account_id, family, games, wins, kills, deaths, score) SELECT id, ?, 1, ?, ?, ?, ? FROM accounts WHERE id = ? "
                        + "ON CONFLICT (account_id, family) DO UPDATE SET games = player_stats.games + 1, wins = player_stats.wins + EXCLUDED.wins, "
                        + "kills = player_stats.kills + EXCLUDED.kills, deaths = player_stats.deaths + EXCLUDED.deaths, "
                        + "score = player_stats.score + EXCLUDED.score, updated_at = now()",
                        result.family(), player.won() ? 1 : 0, player.kills(), player.deaths(), player.score(), player.accountId());
            }
        });
    }

    /**
     * @param accountId An account.
     * @param family A game family.
     * @return The account's totals in that family, all 0 when it has played none.
     */

    @Override
    public Totals getTotals(long accountId, String family) {
        Totals totals = sql.queryOne("SELECT games, wins, kills, deaths, score FROM player_stats WHERE account_id = ? AND family = ?",
                row -> new Totals(row.getInt(1), row.getInt(2), row.getInt(3), row.getInt(4), row.getLong(5)), accountId, family);

        // No row means no games yet.
        return totals == null ? new Totals(0, 0, 0, 0, 0) : totals;
    }

    /**
     * Replaces an account's ladder values.
     * @param account The account writing its own values.
     * @param family The game family.
     * @param type The game's ladder type.
     * @param stats The values, at most 100.
     */

    @Override
    public void writeLadder(Account account, String family, int type, int[] stats) {
        sql.update("INSERT INTO ladder_stats (account_id, family, ladder_type, stats) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (account_id, family, ladder_type) DO UPDATE SET stats = EXCLUDED.stats, updated_at = now()", account.id(), family, type, stats);
    }

    /**
     * @param accountId An account.
     * @param family The game family.
     * @param type The game's ladder type.
     * @return Its ladder values, or null when it has written none.
     */

    @Override
    public int[] readLadder(long accountId, String family, int type) {
        return sql.queryOne("SELECT l.stats FROM ladder_stats l WHERE l.account_id = ? AND l.family = ? AND l.ladder_type = ?",
                row -> readStats(row.getArray(1)), accountId, family, type);
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
    public LadderPage getLadderPage(String family, int type, int index, boolean descending, int start, int count) {
        String order = VALUE + direction(descending);
        String query = "SELECT * FROM (SELECT RANK() OVER (ORDER BY " + order + ") AS position, ROW_NUMBER() OVER (ORDER BY " + order + ", l.account_id) AS place, "
                + "l.account_id, a.name, l.stats FROM ladder_stats l JOIN accounts a ON a.id = l.account_id WHERE l.family = ? AND l.ladder_type = ?) ranked "
                + "WHERE place >= ? ORDER BY place LIMIT ?";

        List<LadderRow> rows = sql.queryAll(query, row -> new LadderRow(row.getInt("position"), row.getLong("account_id"), row.getString("name"),
                readStats(row.getArray("stats"))), index + 1, index + 1, family, type, start, count);

        return new LadderPage(countLadder(family, type), rows);
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
    public Position getLadderPosition(long accountId, String family, int type, int index, boolean descending) {
        // Better means higher when descending, lower otherwise.
        String better = descending ? " > " : " < ";
        String query = "SELECT 1 + (SELECT count(*) FROM ladder_stats l WHERE l.family = m.family AND l.ladder_type = m.ladder_type AND " + VALUE + better
                + "COALESCE(m.stats[?], 0)) FROM ladder_stats m WHERE m.account_id = ? AND m.family = ? AND m.ladder_type = ?";

        Integer position = sql.queryOne(query, row -> row.getInt(1), index + 1, index + 1, accountId, family, type);

        // No row means the account is not on this ladder.
        return new Position(position == null ? 0 : position, countLadder(family, type));
    }

    /**
     * Replaces an account's career bytes.
     * @param account The account writing its own bytes.
     * @param family The game family.
     * @param stats The bytes, at most 256.
     */

    @Override
    public void writeCareer(Account account, String family, byte[] stats) {
        sql.update("INSERT INTO career_stats (account_id, family, stats) VALUES (?, ?, ?) "
                + "ON CONFLICT (account_id, family) DO UPDATE SET stats = EXCLUDED.stats, updated_at = now()", account.id(), family, stats);
    }

    /**
     * @param accountId An account.
     * @param family The game family.
     * @return Its career bytes, or null when it has written none.
     */

    @Override
    public byte[] readCareer(long accountId, String family) {
        return sql.queryOne("SELECT stats FROM career_stats WHERE account_id = ? AND family = ?", row -> row.getBytes(1), accountId, family);
    }

    /**
     * @param family The game family.
     * @param type The game's ladder type.
     * @return How many accounts the ladder holds.
     */

    private int countLadder(String family, int type) {
        return sql.queryOne("SELECT count(*) FROM ladder_stats WHERE family = ? AND ladder_type = ?", row -> row.getInt(1), family, type);
    }

    /**
     * @param descending Whether the highest value comes first.
     * @return The SQL sort direction.
     */

    private static String direction(boolean descending) {
        // The sort follows the game's request.
        return descending ? " DESC" : " ASC";
    }

    /**
     * @param array A stats column.
     * @return Its values.
     * @throws SQLException When the array cannot be read.
     */

    private static int[] readStats(Array array) throws SQLException {
        // The driver hands an integer[] column back boxed.
        Integer[] values = (Integer[]) array.getArray();
        int[] stats = new int[values.length];

        for (int i = 0; i < values.length; i++) stats[i] = values[i];

        return stats;
    }
}
