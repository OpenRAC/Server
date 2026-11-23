package com.openrac.net.packets.out;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.model.OutputBuffer;

/**
 * The link between a game server and the hub that lists it: its id, its heartbeat and players'
 * session checks.
 */

public class HubPacket extends OutgoingPacket {
    /**
     * A hub opcode with no payload: CLOSING.
     * @param opcode The hub opcode.
     */

    public HubPacket(Opcodes.Hub opcode) {
        super(Packets.HUB.ordinal(), opcode.ordinal());
    }

    /**
     * REGISTERED from the hub: the id the server is listed under.
     * @param serverId The server's id in the hub's list.
     */

    public HubPacket(int serverId) {
        this(Opcodes.Hub.REGISTERED);

        writeInt(serverId);
    }

    /**
     * STATUS from a server: how busy it is, sent every few seconds.
     * @param players How many players it has now.
     * @param parties How many parties it runs now.
     */

    public HubPacket(int players, int parties) {
        this(Opcodes.Hub.STATUS);

        writeInt(players);
        writeInt(parties);
    }

    /**
     * VERIFY from a server: whose session a player's token is.
     * @param request The server's number for the question, sent back with the answer.
     * @param token The token the player gave.
     */

    public HubPacket(int request, String token) {
        this(Opcodes.Hub.VERIFY);

        writeInt(request);
        writeString(token);
    }

    /**
     * A hub opcode carrying serialized data: VERIFIED with an answer.
     * @param opcode The hub opcode.
     * @param data The serialized answer.
     */

    public HubPacket(Opcodes.Hub opcode, OutputBuffer data) {
        this(opcode);

        append(data);
    }
}
