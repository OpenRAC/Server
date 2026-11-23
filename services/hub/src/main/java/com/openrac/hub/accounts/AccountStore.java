package com.openrac.hub.accounts;

import java.time.Instant;

/**
 * Where the hub keeps accounts and their sessions. Every call may block, so the hub only makes them
 * off its event loops. Sessions are found by the hash of their token, never the token itself.
 */

public interface AccountStore {
    /**
     * @param name An account name, in any case.
     * @return The account, or null when nobody has registered the name.
     */

    Account find(String name);

    /**
     * Registers a name.
     * @param name The name as the player typed it.
     * @param passwordHash The password's hash.
     * @return The new account, or null when the name was taken meanwhile.
     */

    Account create(String name, String passwordHash);

    /**
     * Notes a successful login.
     * @param accountId The account that logged in.
     */

    void touch(long accountId);

    /**
     * Starts a session.
     * @param tokenHash The hash of the session's token.
     * @param accountId The account it belongs to.
     * @param expires When the token stops working.
     */

    void addSession(String tokenHash, long accountId, Instant expires);

    /**
     * @param tokenHash The hash of a session's token.
     * @param now The time to check expiry against.
     * @return The session's account, or null when the token is unknown or expired.
     */

    Account findSession(String tokenHash, Instant now);

    /**
     * Ends a session.
     * @param tokenHash The hash of the session's token.
     */

    void removeSession(String tokenHash);

    /**
     * Forgets every expired session.
     * @param now The time to check expiry against.
     */

    void removeExpired(Instant now);
}
