package com.openrac.hub.accounts;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Accounts kept in memory, for a hub with no database: a LAN party or a private box. Everything is
 * gone when the hub stops. The tests use it too.
 */

public class MemoryAccountStore implements AccountStore {
    private final Map<String, Account> byName = new HashMap<>(); // Keyed by the lower-case name.
    private final Map<Long, Account> byId = new HashMap<>();
    private final Map<String, Held> sessions = new HashMap<>(); // Keyed by token hash.

    private long nextId = 1;

    /**
     * @param name An account name, in any case.
     * @return The account, or null when nobody has registered the name.
     */

    @Override
    public synchronized Account find(String name) {
        return byName.get(fold(name));
    }

    /**
     * Registers a name.
     * @param name The name as the player typed it.
     * @param passwordHash The password's hash.
     * @return The new account, or null when the name was taken meanwhile.
     */

    @Override
    public synchronized Account create(String name, String passwordHash) {
        // Names are unique in any case, as in the database.
        if (byName.containsKey(fold(name))) return null;

        Account account = new Account(nextId++, name, passwordHash);

        byName.put(fold(name), account);
        byId.put(account.id(), account);

        return account;
    }

    /**
     * Nothing to note: memory keeps no login times.
     * @param accountId The account that logged in.
     */

    @Override
    public void touch(long accountId) {}

    /**
     * Starts a session.
     * @param tokenHash The hash of the session's token.
     * @param accountId The account it belongs to.
     * @param expires When the token stops working.
     */

    @Override
    public synchronized void addSession(String tokenHash, long accountId, Instant expires) {
        sessions.put(tokenHash, new Held(accountId, expires));
    }

    /**
     * @param tokenHash The hash of a session's token.
     * @param now The time to check expiry against.
     * @return The session's account, or null when the token is unknown or expired.
     */

    @Override
    public synchronized Account findSession(String tokenHash, Instant now) {
        Held held = sessions.get(tokenHash);

        // An expired token reads as unknown until the sweep forgets it.
        if (held == null || !held.expires().isAfter(now)) return null;

        return byId.get(held.accountId());
    }

    /**
     * Ends a session.
     * @param tokenHash The hash of the session's token.
     */

    @Override
    public synchronized void removeSession(String tokenHash) {
        sessions.remove(tokenHash);
    }

    /**
     * Forgets every expired session.
     * @param now The time to check expiry against.
     */

    @Override
    public synchronized void removeExpired(Instant now) {
        sessions.values().removeIf(held -> !held.expires().isAfter(now));
    }

    /**
     * @param name An account name.
     * @return The name as the unique index compares it.
     */

    private static String fold(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * A session as memory holds it.
     * @param accountId The account it belongs to.
     * @param expires When its token stops working.
     */

    private record Held(long accountId, Instant expires) {}
}
