package com.openrac.hub.net.packets.in;

import com.openrac.hub.Constants;
import com.openrac.hub.accounts.Account;
import com.openrac.hub.accounts.AccountController;
import com.openrac.hub.net.Connection;
import com.openrac.hub.net.packets.model.IncomingPacket;
import com.openrac.hub.stats.StatsController;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.ErrorPacket;
import com.openrac.utils.Utils;

/**
 * Answers a client's stats and ladder requests, and stores its own ladder and career values.
 */

public class IncomingStatsPacket extends IncomingPacket {
    /**
     * Creates the packet; only an introduced connection may ask.
     */

    public IncomingStatsPacket() {
        super(true);
    }

    /**
     * Reads the opcode and runs it.
     * @param connection The connection that sent the request.
     * @param payload The stats packet's payload.
     */

    @Override
    public void handle(Connection connection, InputBuffer payload) {
        // Servers report games through HUB RESULT instead.
        if (!connection.isGuest(Packets.STATS)) return;

        Opcodes.Stats opcode = Opcodes.Stats.get(payload.readUnsignedByte());
        String family = payload.readString();

        // A family is compared, never shown, so anything else is broken.
        if (opcode == null || !Utils.isWord(family, Constants.MAX_FAMILY_LENGTH)) {
            connection.reject("sent an invalid stats request");
            return;
        }

        switch (opcode) {
            case TOTALS -> StatsController.totals(connection.getChannel(), family, payload.readLong());
            case LADDER_READ -> StatsController.readLadder(connection.getChannel(), family, payload.readLong(), payload.readInt());
            case CAREER_READ -> StatsController.readCareer(connection.getChannel(), family, payload.readLong());
            case LADDER_PAGE -> handleLadderPage(connection, family, payload);
            case LADDER_POSITION -> handleLadderPosition(connection, family, payload);
            case LADDER_WRITE -> handleLadderWrite(connection, family, payload);
            case CAREER_WRITE -> handleCareerWrite(connection, family, payload);
        }
    }

    /**
     * Reads one page of a ladder.
     * @param connection The connection that sent the request.
     * @param family The game family.
     * @param payload The rest of the request.
     */

    private void handleLadderPage(Connection connection, String family, InputBuffer payload) {
        int type = payload.readInt();
        int index = readStatIndex(connection, payload);
        boolean descending = payload.readUnsignedByte() != 0;
        int start = Math.max(1, payload.readInt());
        int count = Math.min(Constants.MAX_LADDER_PAGE, payload.readUnsignedByte());

        // A bad index already closed the connection.
        if (index >= 0) StatsController.page(connection.getChannel(), family, type, index, descending, start, count);
    }

    /**
     * Reads where an account stands on a ladder.
     * @param connection The connection that sent the request.
     * @param family The game family.
     * @param payload The rest of the request.
     */

    private void handleLadderPosition(Connection connection, String family, InputBuffer payload) {
        long accountId = payload.readLong();
        int type = payload.readInt();
        int index = readStatIndex(connection, payload);
        boolean descending = payload.readUnsignedByte() != 0;

        // A bad index already closed the connection.
        if (index >= 0) StatsController.position(connection.getChannel(), family, accountId, type, index, descending);
    }

    /**
     * Stores the logged-in account's ladder row.
     * @param connection The connection that sent the request.
     * @param family The game family.
     * @param payload The rest of the request.
     */

    private void handleLadderWrite(Connection connection, String family, InputBuffer payload) {
        Account account = getAccount(connection);
        int type = payload.readInt();
        int count = payload.readUnsignedByte();

        // A row past the game's format only comes from a broken client.
        if (count > Constants.MAX_LADDER_STATS) {
            connection.reject("sent too many ladder values");
            return;
        }

        int[] stats = new int[count];

        for (int i = 0; i < count; i++) stats[i] = payload.readInt();

        // Not logged in: already told NOT_LOGGED_IN.
        if (account != null) StatsController.writeLadder(connection.getChannel(), account, family, type, stats);
    }

    /**
     * Stores the logged-in account's career bytes.
     * @param connection The connection that sent the request.
     * @param family The game family.
     * @param payload The rest of the request.
     */

    private void handleCareerWrite(Connection connection, String family, InputBuffer payload) {
        Account account = getAccount(connection);
        byte[] stats = payload.readRemainingBytes();

        // Career bytes past the game's format only come from a broken client.
        if (stats.length > Constants.MAX_CAREER_BYTES) {
            connection.reject("sent too many career bytes");
            return;
        }

        // Not logged in: already told NOT_LOGGED_IN.
        if (account != null) StatsController.writeCareer(connection.getChannel(), account, family, stats);
    }

    /**
     * Reads which ladder value to sort by, closing a connection that names one past the row.
     * @param connection The connection that sent the request.
     * @param payload The rest of the request.
     * @return The index, or -1 when it was invalid and the connection is closed.
     */

    private int readStatIndex(Connection connection, InputBuffer payload) {
        int index = payload.readUnsignedByte();

        // A ladder row holds only so many values.
        if (index >= Constants.MAX_LADDER_STATS) {
            connection.reject("sent an invalid ladder value index");
            return -1;
        }

        return index;
    }

    /**
     * @param connection The connection that sent the request.
     * @return The account this client logged in to, or null after telling it NOT_LOGGED_IN.
     */

    private Account getAccount(Connection connection) {
        Account account = AccountController.getAccount(connection.getChannel());

        // Only a logged-in player writes its own stats.
        if (account == null) connection.getContext().writeAndFlush(new ErrorPacket(Opcodes.Error.NOT_LOGGED_IN));

        return account;
    }
}
