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
     * A party opcode with no payload: CREATE, LEAVE or END.
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
     * A party opcode carrying one session id: LEFT.
     * @param opcode The party opcode.
     * @param sessionId The session the notice is about.
     */

    public PartyPacket(Opcodes.Party opcode, int sessionId) {
        this(opcode);

        writeInt(sessionId);
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
