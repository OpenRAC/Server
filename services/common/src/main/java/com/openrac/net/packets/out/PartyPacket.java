package com.openrac.net.packets.out;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.model.OutputBuffer;

/**
 * Creating, joining and leaving a party, and the server's notices about it.
 */

public class PartyPacket extends OutgoingPacket {
    /**
     * A party opcode with no payload: CREATE, LEAVE, END or KICKED.
     * @param opcode The party opcode.
     */

    public PartyPacket(Opcodes.Party opcode) {
        super(Packets.PARTY.ordinal(), opcode.ordinal());
    }

    /**
     * A party opcode carrying a join code: JOIN.
     * @param opcode The party opcode.
     * @param code The party's join code.
     */

    public PartyPacket(Opcodes.Party opcode, String code) {
        this(opcode);

        writeString(code);
    }

    /**
     * A party opcode carrying one session id: LEFT or KICK.
     * @param opcode The party opcode.
     * @param sessionId The session the notice is about.
     */

    public PartyPacket(Opcodes.Party opcode, int sessionId) {
        this(opcode);

        writeInt(sessionId);
    }

    /**
     * RESUME from the server: a member's connection went away, or it came back.
     * @param sessionId The member.
     * @param back Whether it came back.
     */

    public PartyPacket(int sessionId, boolean back) {
        this(Opcodes.Party.RESUME);

        writeInt(sessionId);
        writeUnsignedByte(back ? 1 : 0);
    }

    /**
     * A party opcode carrying serialized data: INFO with a party, JOINED with a member.
     * @param opcode The party opcode.
     * @param data The serialized party or member.
     */

    public PartyPacket(Opcodes.Party opcode, OutputBuffer data) {
        this(opcode);

        append(data);
    }
}
