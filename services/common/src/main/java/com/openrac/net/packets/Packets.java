package com.openrac.net.packets;

/**
 * Every packet type on the wire. A constant's ordinal is the packet id, the first byte of every
 * frame, so new packets are only ever appended.
 */

public enum Packets {
    HELLO,
    PARTY,
    CHAT,
    DATA,
    ERROR,
    TIME,
    UDP,
    RELAY,
    REGIONS,
    REGISTER,
    UI, // Claimed by the UI descriptions, Server PR #4.
    HUB,
    SERVERS,
    ACCOUNT,
    STATS;

    /**
     * Resolves a packet from its id.
     * @param id The packet id read from the wire.
     * @return The matching packet, or null if the id is out of range.
     */

    public static Packets getFromByte(int id) {
        Packets[] all = values();

        // An id past the last packet is no packet at all.
        if (id < 0 || id >= all.length) return null;

        return all[id];
    }
}
