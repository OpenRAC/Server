package com.openrac.net.packets;

/**
 * Opcodes grouped by the packet that carries them. Each ordinal is the byte written right after the
 * packet id, so values are only ever appended.
 */

public final class Opcodes {

    private Opcodes() {}

    /**
     * Opcodes of the PARTY packet: creating, joining, listing and running parties.
     */

    public enum Party {
        CREATE, // Client asks for a new party it hosts.
        JOIN, // Client joins a party by its code.
        LEAVE, // Client leaves its party.
        INFO, // Server describes the party to a new member.
        JOINED, // Server tells members someone joined.
        LEFT, // Server tells members someone left.
        END, // Server tells members the host left.
        FIND, // Client joins any party with room, or hosts a new one.
        LIST, // Client asks for the parties it could join; the server answers with them.
        HOST_CHANGED, // Host names who follows it, a new host declines; the server tells who hosts now.
        RESUME, // Client rejoins after a drop; the server tells members who went away and came back.
        KICK, // The host asks the server to remove a member.
        KICKED, // Server tells a member the host removed it.
        ADVERT, // Host sets the opaque bytes party lists show with its party.
        SLOTS; // Server tells slot readers each member's stable slot.

        /**
         * Resolves a party opcode from its byte.
         * @param id The opcode byte read from the wire.
         * @return The matching opcode, or null if the byte is out of range.
         */

        public static Party get(int id) {
            return Opcodes.get(values(), id);
        }
    }

    /**
     * Reasons the ERROR packet gives for a refused party or hub request.
     */

    public enum Error {
        PARTY_NOT_FOUND,
        PARTY_FULL,
        ALREADY_IN_PARTY,
        NOT_IN_PARTY,
        NOT_HOST,
        RESUME_REFUSED, // Its slot is gone, or the token is wrong.
        OTHER_GAME, // The party plays another game family; its family follows.
        REGION_KEY, // The hub asks servers for a key this one lacks.
        HUB_FULL, // The hub lists as many servers as it takes.
        WRONG_PASSWORD, // The account exists and the password is not its own.
        INVALID_ACCOUNT, // The name or password breaks the hub's rules.
        SESSION_INVALID, // The session token is unknown, expired or ended.
        TRY_LATER, // Too many attempts, or the hub cannot answer now.
        NOT_LOGGED_IN; // Writing stats needs an account login on this connection.

        /**
         * Resolves an error reason from its byte.
         * @param id The opcode byte read from the wire.
         * @return The matching reason, or null if the byte is out of range.
         */

        public static Error get(int id) {
            return Opcodes.get(values(), id);
        }
    }

    /**
     * Opcodes of the HUB packet: a game server's link to the hub that lists it.
     */

    public enum Hub {
        REGISTERED, // Hub tells a server it is listed, and under which id.
        STATUS, // Server heartbeat with its players and parties.
        CLOSING, // Server is shutting down, so the hub stops listing it.
        VERIFY, // Server asks who a player's session token belongs to.
        VERIFIED, // Hub answers VERIFY with the account, or none.
        RESULT; // Server reports a finished game for the history and stats.

        /**
         * Resolves a hub opcode from its byte.
         * @param id The opcode byte read from the wire.
         * @return The matching opcode, or null if the byte is out of range.
         */

        public static Hub get(int id) {
            return Opcodes.get(values(), id);
        }
    }

    /**
     * Opcodes of the SERVERS packet: the hub keeping a client's server list current.
     */

    public enum Servers {
        WATCH, // Client asks for every listed server now, then each change.
        ADDED, // Hub describes a server that joined the list.
        CHANGED, // Hub gives a listed server's new player and party counts.
        REMOVED; // Hub says a server left the list.

        /**
         * Resolves a servers opcode from its byte.
         * @param id The opcode byte read from the wire.
         * @return The matching opcode, or null if the byte is out of range.
         */

        public static Servers get(int id) {
            return Opcodes.get(values(), id);
        }
    }

    /**
     * Opcodes of the ACCOUNT packet: logging in at the hub, and proving it at a server.
     */

    public enum Account {
        LOGIN, // Client logs in by name and password, registering a new name.
        LOGGED_IN, // Hub or server says who the client is now.
        TOKEN, // Client proves its hub login to a server.
        LOGOUT; // Client ends its session at the hub.

        /**
         * Resolves an account opcode from its byte.
         * @param id The opcode byte read from the wire.
         * @return The matching opcode, or null if the byte is out of range.
         */

        public static Account get(int id) {
            return Opcodes.get(values(), id);
        }
    }

    /**
     * Opcodes of the STATS packet: a player's totals and the game's own ladders, kept by the hub.
     * Each LADDER and CAREER opcode answers one of the game's Medius stats requests.
     */

    public enum Stats {
        TOTALS, // A player's games, wins, kills, deaths and score in a family.
        LADDER_WRITE, // Client stores its own ladder values (LADDER_STATS_WRITE).
        LADDER_READ, // An account's ladder values (LADDER_STATS_READ).
        LADDER_PAGE, // A page of the ladder sorted by one value (LADDER_PAGE).
        LADDER_POSITION, // Where an account stands on the ladder (LADDER_POSITION).
        CAREER_WRITE, // Client stores its own career bytes (CAREER_STATS_UPLOAD).
        CAREER_READ; // An account's career bytes, as account info shows them.

        /**
         * Resolves a stats opcode from its byte.
         * @param id The opcode byte read from the wire.
         * @return The matching opcode, or null if the byte is out of range.
         */

        public static Stats get(int id) {
            return Opcodes.get(values(), id);
        }
    }

    /**
     * Resolves any opcode enum's value from its byte.
     * @param values The enum's values, in wire order.
     * @param id The opcode byte read from the wire.
     * @param <E> The opcode enum.
     * @return The matching value, or null if the byte is out of range.
     */

    private static <E extends Enum<E>> E get(E[] values, int id) {
        // Past either end is no opcode at all.
        if (id < 0 || id >= values.length) return null;

        return values[id];
    }
}
