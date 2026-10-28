package com.openrac.net.packets;

/**
 * Opcodes grouped by the packet that carries them. Each ordinal is the byte written right after the
 * packet id, so values are only ever appended.
 */

public final class Opcodes {

    private Opcodes() {}

    public enum Party {
        CREATE, // Client asks for a new party it hosts.
        JOIN, // Client joins a party by its code.
        LEAVE, // Client leaves its party.
        INFO, // Server describes the party to a new member.
        JOINED, // Server tells members someone joined.
        LEFT, // Server tells members someone left.
        END, // Server tells members the host left.
        FIND, // Client joins any party with room, or hosts a new one.
        LIST; // Client asks for the parties it could join; the server answers with them.

        /**
         * Resolves a party opcode from its byte.
         * @param id The opcode byte read from the wire.
         * @return The matching opcode, or null if the byte is out of range.
         */

        public static Party get(int id) {
            // Past either end is no opcode at all.
            if (id < 0 || id >= values().length) return null;

            return values()[id];
        }
    }

    public enum Error {
        PARTY_NOT_FOUND,
        PARTY_FULL,
        ALREADY_IN_PARTY,
        NOT_IN_PARTY;

        /**
         * Resolves an error reason from its byte.
         * @param id The opcode byte read from the wire.
         * @return The matching reason, or null if the byte is out of range.
         */

        public static Error get(int id) {
            // Past either end is no reason at all.
            if (id < 0 || id >= values().length) return null;

            return values()[id];
        }
    }
}
