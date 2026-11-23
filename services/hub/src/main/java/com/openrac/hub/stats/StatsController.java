package com.openrac.hub.stats;

import io.netty.channel.Channel;

import com.openrac.hub.Background;
import com.openrac.hub.accounts.Account;
import com.openrac.hub.servers.GameServer;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.model.GameResult;
import com.openrac.net.packets.model.OutputBuffer;
import com.openrac.net.packets.out.StatsPacket;
import com.openrac.utils.logger.Log;

/**
 * Keeps the stats: finished games that game servers report, and the game's own ladder and career
 * values its players write. Each request runs off the event loops and answers down its connection.
 */

public class StatsController {
    private static volatile StatsStore store = new MemoryStatsStore();

    /**
     * Stores a game a server reports as finished.
     * @param server The server that reported it.
     * @param channel Its connection.
     * @param result The game.
     */

    public static void record(GameServer server, Channel channel, GameResult result) {
        Background.run(channel, () -> {
            store.recordGame(server.getRegion(), server.getName(), result);

            Log.info("Server " + server.getId() + " reported a game of " + result.players().size() + " players in " + result.family() + ".");
        });
    }

    /**
     * Answers an account's totals.
     * @param channel The asking connection.
     * @param family The game family.
     * @param accountId The account.
     */

    public static void totals(Channel channel, String family, long accountId) {
        Background.run(channel, () -> {
            StatsStore.Totals totals = store.getTotals(accountId, family);
            OutputBuffer answer = new OutputBuffer();

            answer.writeLong(accountId);
            answer.writeString(family);
            answer.writeInt(totals.games());
            answer.writeInt(totals.wins());
            answer.writeInt(totals.kills());
            answer.writeInt(totals.deaths());
            answer.writeLong(totals.score());

            channel.writeAndFlush(new StatsPacket(Opcodes.Stats.TOTALS, answer));
        });
    }

    /**
     * Stores a player's own ladder values.
     * @param channel The player's connection.
     * @param account Its account.
     * @param family The game family.
     * @param type The game's ladder type.
     * @param stats The values.
     */

    public static void writeLadder(Channel channel, Account account, String family, int type, int[] stats) {
        Background.run(channel, () -> {
            store.writeLadder(account, family, type, stats);
            channel.writeAndFlush(new StatsPacket(Opcodes.Stats.LADDER_WRITE));
        });
    }

    /**
     * Answers an account's ladder values.
     * @param channel The asking connection.
     * @param family The game family.
     * @param accountId The account.
     * @param type The game's ladder type.
     */

    public static void readLadder(Channel channel, String family, long accountId, int type) {
        Background.run(channel, () -> {
            OutputBuffer answer = new OutputBuffer();

            answer.writeLong(accountId);
            answer.writeInt(type);
            writeStats(answer, store.readLadder(accountId, family, type));

            channel.writeAndFlush(new StatsPacket(Opcodes.Stats.LADDER_READ, answer));
        });
    }

    /**
     * Answers a page of a ladder.
     * @param channel The asking connection.
     * @param family The game family.
     * @param type The game's ladder type.
     * @param index The value to sort by.
     * @param descending Whether the highest value comes first.
     * @param start The first position, from 1.
     * @param count The most rows.
     */

    public static void page(Channel channel, String family, int type, int index, boolean descending, int start, int count) {
        Background.run(channel, () -> {
            StatsStore.LadderPage page = store.getLadderPage(family, type, index, descending, start, count);
            OutputBuffer answer = new OutputBuffer();

            answer.writeInt(page.total());
            answer.writeUnsignedByte(page.rows().size());

            for (StatsStore.LadderRow row : page.rows()) {
                answer.writeInt(row.position());
                answer.writeLong(row.accountId());
                answer.writeString(row.name());
                writeStats(answer, row.stats());
            }

            channel.writeAndFlush(new StatsPacket(Opcodes.Stats.LADDER_PAGE, answer));
        });
    }

    /**
     * Answers where an account stands on a ladder.
     * @param channel The asking connection.
     * @param family The game family.
     * @param accountId The account.
     * @param type The game's ladder type.
     * @param index The value to sort by.
     * @param descending Whether the highest value comes first.
     */

    public static void position(Channel channel, String family, long accountId, int type, int index, boolean descending) {
        Background.run(channel, () -> {
            StatsStore.Position position = store.getLadderPosition(accountId, family, type, index, descending);
            OutputBuffer answer = new OutputBuffer();

            answer.writeLong(accountId);
            answer.writeInt(position.position());
            answer.writeInt(position.total());

            channel.writeAndFlush(new StatsPacket(Opcodes.Stats.LADDER_POSITION, answer));
        });
    }

    /**
     * Stores a player's own career bytes.
     * @param channel The player's connection.
     * @param account Its account.
     * @param family The game family.
     * @param stats The bytes.
     */

    public static void writeCareer(Channel channel, Account account, String family, byte[] stats) {
        Background.run(channel, () -> {
            store.writeCareer(account, family, stats);
            channel.writeAndFlush(new StatsPacket(Opcodes.Stats.CAREER_WRITE));
        });
    }

    /**
     * Answers an account's career bytes.
     * @param channel The asking connection.
     * @param family The game family.
     * @param accountId The account.
     */

    public static void readCareer(Channel channel, String family, long accountId) {
        Background.run(channel, () -> {
            byte[] stats = store.readCareer(accountId, family);
            OutputBuffer answer = new OutputBuffer();

            answer.writeLong(accountId);

            // An account that never wrote answers with no bytes.
            if (stats != null) answer.writeBytes(stats);

            channel.writeAndFlush(new StatsPacket(Opcodes.Stats.CAREER_READ, answer));
        });
    }

    /**
     * Writes ladder values as a count byte and the values.
     * @param buffer Where to write them.
     * @param stats The values, or null for none.
     */

    private static void writeStats(OutputBuffer buffer, int[] stats) {
        // No row reads as no values.
        if (stats == null) {
            buffer.writeUnsignedByte(0);
            return;
        }

        buffer.writeUnsignedByte(stats.length);

        for (int value : stats) buffer.writeInt(value);
    }

    /**
     * @param store Where stats are kept from now on.
     */

    public static void setStore(StatsStore store) {
        StatsController.store = store;
    }
}
