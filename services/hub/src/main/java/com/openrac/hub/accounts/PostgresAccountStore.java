package com.openrac.hub.accounts;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;

/**
 * Accounts and sessions in PostgreSQL, in the tables the migrations create. A database error is
 * thrown on as unchecked, and the caller answers the player with TRY_LATER.
 */

public class PostgresAccountStore implements AccountStore {
    private final DataSource pool;

    /**
     * Creates the store.
     * @param pool The database's connection pool.
     */

    public PostgresAccountStore(DataSource pool) {
        this.pool = pool;
    }

    /**
     * @param name An account name, in any case.
     * @return The account, or null when nobody has registered the name.
     */

    @Override
    public Account find(String name) {
        return queryAccount("SELECT id, name, password_hash FROM accounts WHERE lower(name) = lower(?)", name);
    }

    /**
     * Registers a name.
     * @param name The name as the player typed it.
     * @param passwordHash The password's hash.
     * @return The new account, or null when the name was taken meanwhile.
     */

    @Override
    public Account create(String name, String passwordHash) {
        String sql = "INSERT INTO accounts (name, password_hash) VALUES (?, ?) ON CONFLICT DO NOTHING RETURNING id, name, password_hash";

        return queryAccount(sql, name, passwordHash);
    }

    /**
     * Notes a successful login.
     * @param accountId The account that logged in.
     */

    @Override
    public void touch(long accountId) {
        update("UPDATE accounts SET last_login = now() WHERE id = ?", accountId);
    }

    /**
     * Starts a session.
     * @param tokenHash The hash of the session's token.
     * @param accountId The account it belongs to.
     * @param expires When the token stops working.
     */

    @Override
    public void addSession(String tokenHash, long accountId, Instant expires) {
        update("INSERT INTO sessions (token_hash, account_id, expires_at) VALUES (?, ?, ?)", tokenHash, accountId, Timestamp.from(expires));
    }

    /**
     * @param tokenHash The hash of a session's token.
     * @param now The time to check expiry against.
     * @return The session's account, or null when the token is unknown or expired.
     */

    @Override
    public Account findSession(String tokenHash, Instant now) {
        String sql = "SELECT a.id, a.name, a.password_hash FROM sessions s JOIN accounts a ON a.id = s.account_id WHERE s.token_hash = ? AND s.expires_at > ?";

        return queryAccount(sql, tokenHash, Timestamp.from(now));
    }

    /**
     * Ends a session.
     * @param tokenHash The hash of the session's token.
     */

    @Override
    public void removeSession(String tokenHash) {
        update("DELETE FROM sessions WHERE token_hash = ?", tokenHash);
    }

    /**
     * Forgets every expired session.
     * @param now The time to check expiry against.
     */

    @Override
    public void removeExpired(Instant now) {
        update("DELETE FROM sessions WHERE expires_at <= ?", Timestamp.from(now));
    }

    /**
     * Runs a query that returns at most one account.
     * @param sql The query, with a ? per value.
     * @param values The values, in order.
     * @return The account, or null when no row came back.
     */

    private Account queryAccount(String sql, Object... values) {
        try (Connection connection = pool.getConnection(); PreparedStatement statement = prepare(connection, sql, values);
                ResultSet row = statement.executeQuery()) {
            // No row: an unknown name or session, or a name taken meanwhile.
            if (!row.next()) return null;

            return new Account(row.getLong("id"), row.getString("name"), row.getString("password_hash"));
        } catch (SQLException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    /**
     * Runs a statement that returns no rows.
     * @param sql The statement, with a ? per value.
     * @param values The values, in order.
     */

    private void update(String sql, Object... values) {
        try (Connection connection = pool.getConnection(); PreparedStatement statement = prepare(connection, sql, values)) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    /**
     * Prepares a statement and binds its values.
     * @param connection The pooled connection.
     * @param sql The statement, with a ? per value.
     * @param values The values, in order.
     * @return The bound statement.
     * @throws SQLException When the statement does not prepare.
     */

    private static PreparedStatement prepare(Connection connection, String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);

        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);

        return statement;
    }
}
