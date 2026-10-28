package com.openrac.net.datagrams;

/**
 * Every datagram kind on the UDP socket. A constant's ordinal is the kind byte, so new kinds are
 * only ever appended.
 */

public enum Datagrams {
    BIND, // Client binds or refreshes its address, with no payload.
    STATE; // Game state: a target or sender id, then opaque bytes.

    /**
     * Resolves a datagram kind from its byte.
     * @param id The kind byte read from the datagram.
     * @return The matching kind, or null if the byte is out of range.
     */

    public static Datagrams get(int id) {
        // Past either end is no kind at all.
        if (id < 0 || id >= values().length) return null;

        return values()[id];
    }
}
