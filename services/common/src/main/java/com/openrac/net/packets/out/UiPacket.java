package com.openrac.net.packets.out;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;

/**
 * The screens a server describes for a game's multiplayer menus, and the requests about them.
 */

public class UiPacket extends OutgoingPacket {
    /**
     * A UI opcode carrying one string: GET (the game family) or NOTICE (the line to show).
     * @param opcode The UI opcode.
     * @param text The string it carries.
     */

    public UiPacket(Opcodes.Ui opcode, String text) {
        super(Packets.UI.ordinal(), opcode.ordinal());

        writeString(text);
    }

    /**
     * DESCRIPTION: the server's revision of its screens and their JSON, empty for the game's own.
     * @param revision The description's revision.
     * @param json The description's UTF-8 JSON bytes.
     */

    public UiPacket(int revision, byte[] json) {
        super(Packets.UI.ordinal(), Opcodes.Ui.DESCRIPTION.ordinal());

        writeInt(revision);
        writeBytes(json);
    }

    /**
     * ACTION: a server action the player chose, and the value it carries.
     * @param key The action's key.
     * @param value The value chosen.
     */

    public UiPacket(String key, int value) {
        super(Packets.UI.ordinal(), Opcodes.Ui.ACTION.ordinal());

        writeString(key);
        writeInt(value);
    }
}
