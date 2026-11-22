package com.openrac.hub.accounts;

import com.openrac.hub.database.Sql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Accounts and sessions in PostgreSQL, in the tables the migrations create.
 */

public class PostgresAccountStore implements AccountStore {
    private static final String ACCOUNT_COLUMNS = "a.id, a.name, a.password_hash";

    private final Sql sql;

    /**
     * Creates the store.
     * @param sql The runner on the database's pool.
     */

    public PostgresAccountStore(Sql sql) {
        this.sql = sql;
    }

    /**
     * @param name An account name, in any case.
     * @return The account, or null when nobody has registered the name.
     */

    @Override
    public Account find(String name) {
        return sql.queryOne("SELECT " + ACCOUNT_COLUMNS + " FROM accounts a WHERE lower(a.name) = lower(?)", PostgresAccountStore::readAccount, name);
    }

    /**
     * Registers a name.
     * @param name The name as the player typed it.
     * @param passwordHash The password's hash.
     * @return The new account, or null when the name was taken meanwhile.
     */

    @Override
    public Account create(String name, String passwordHash) {
        String insert = "INSERT INTO accounts AS a (name, password_hash) VALUES (?, ?) ON CONFLICT DO NOTHING RETURNING " + ACCOUNT_COLUMNS;

        return sql.queryOne(insert, PostgresAccountStore::readAccount, name, passwordHash);
    }

    /**
     * Notes a successful login.
     * @param accountId The account that logged in.
     */

    @Override
    public void touch(long accountId) {
        sql.update("UPDATE accounts SET last_login = now() WHERE id = ?", accountId);
    }

    /**
     * Starts a session.
     * @param tokenHash The hash of the session's token.
     * @param accountId The account it belongs to.
     * @param expires When the token stops working.
     */

    @Override
    public void addSession(String tokenHash, long accountId, Instant expires) {
        sql.update("INSERT INTO sessions (token_hash, account_id, expires_at) VALUES (?, ?, ?)", tokenHash, accountId, Timestamp.from(expires));
    }

    /**
     * @param tokenHash The hash of a session's token.
     * @param now The time to check expiry against.
     * @return The session's account, or null when the token is unknown or expired.
     */

    @Override
    public Account findSession(String tokenHash, Instant now) {
        String query = "SELECT " + ACCOUNT_COLUMNS + " FROM sessions s JOIN accounts a ON a.id = s.account_id WHERE s.token_hash = ? AND s.expires_at > ?";

        return sql.queryOne(query, PostgresAccountStore::readAccount, tokenHash, Timestamp.from(now));
    }

    /**
     * Ends a session.
     * @param tokenHash The hash of the session's token.
     */

    @Override
    public void removeSession(String tokenHash) {
        sql.update("DELETE FROM sessions WHERE token_hash = ?", tokenHash);
    }

    /**
     * Forgets every expired session.
     * @param now The time to check expiry against.
     */

    @Override
    public void removeExpired(Instant now) {
        sql.update("DELETE FROM sessions WHERE expires_at <= ?", Timestamp.from(now));
    }

    /**
     * @param row A result at an account row.
     * @return The account.
     * @throws SQLException When a column cannot be read.
     */

    private static Account readAccount(ResultSet row) throws SQLException {
        return new Account(row.getLong("id"), row.getString("name"), row.getString("password_hash"));
    }
}
