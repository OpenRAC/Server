package com.openrac.net.packets.out;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;

/**
 * Logging in to an account at the hub, and proving that login to a game server.
 */

public class AccountPacket extends OutgoingPacket {
    /**
     * An account opcode with no payload: LOGOUT.
     * @param opcode The account opcode.
     */

    public AccountPacket(Opcodes.Account opcode) {
        super(Packets.ACCOUNT.ordinal(), opcode.ordinal());
    }

    /**
     * TOKEN from a client: the session token its hub login gave it.
     * @param opcode The account opcode.
     * @param token The session token.
     */

    public AccountPacket(Opcodes.Account opcode, String token) {
        this(opcode);

        writeString(token);
    }

    /**
     * LOGIN from a client: a name and its password. A name nobody has registers.
     * @param name The account name.
     * @param password The password.
     */

    public AccountPacket(String name, String password) {
        this(Opcodes.Account.LOGIN);

        writeString(name);
        writeString(password);
    }

    /**
     * LOGGED_IN from the hub or a server: who the client is.
     * @param accountId The account's id.
     * @param name The account's name as registered.
     * @param token The session token from the hub, empty from a server.
     * @param created Whether this login registered the account.
     */

    public AccountPacket(long accountId, String name, String token, boolean created) {
        this(Opcodes.Account.LOGGED_IN);

        writeLong(accountId);
        writeString(name);
        writeString(token);
        writeUnsignedByte(created ? 1 : 0);
    }
}
