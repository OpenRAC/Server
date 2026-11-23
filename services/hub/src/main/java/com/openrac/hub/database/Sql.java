package com.openrac.hub.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Runs the stores' statements on the pool, or on one connection inside a transaction. A database
 * error is thrown on as unchecked, and the caller answers the player with TRY_LATER.
 */

public class Sql {
    private final DataSource pool; // Null inside a transaction.
    private final Connection bound; // The transaction's connection, else null.

    /**
     * Creates a runner on the pool.
     * @param pool The database's connection pool.
     */

    public Sql(DataSource pool) {
        this(pool, null);
    }

    /**
     * Creates a runner on the pool or bound to one connection.
     * @param pool The pool, or null.
     * @param bound The transaction's connection, or null.
     */

    private Sql(DataSource pool, Connection bound) {
        this.pool = pool;
        this.bound = bound;
    }

    /**
     * Runs a query and reads its first row.
     * @param sql The query, with a ? per value.
     * @param reader Reads a row.
     * @param values The values, in order.
     * @param <T> What a row reads as.
     * @return The first row, or null when none came back.
     */

    public <T> T queryOne(String sql, RowReader<T> reader, Object... values) {
        List<T> rows = queryAll(sql, reader, values);

        // No row: an unknown name, session or account.
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * Runs a query and reads every row.
     * @param sql The query, with a ? per value.
     * @param reader Reads a row.
     * @param values The values, in order.
     * @param <T> What a row reads as.
     * @return The rows, in the query's order.
     */

    public <T> List<T> queryAll(String sql, RowReader<T> reader, Object... values) {
        return run(connection -> {
            List<T> rows = new ArrayList<>();

            try (PreparedStatement statement = prepare(connection, sql, values); ResultSet row = statement.executeQuery()) {
                while (row.next()) rows.add(reader.read(row));
            }

            return rows;
        });
    }

    /**
     * Runs a statement that returns no rows.
     * @param sql The statement, with a ? per value.
     * @param values The values, in order.
     */

    public void update(String sql, Object... values) {
        run(connection -> {
            try (PreparedStatement statement = prepare(connection, sql, values)) {
                return statement.executeUpdate();
            }
        });
    }

    /**
     * Runs several statements as one: all of them happen, or none.
     * @param work The statements, run on a runner bound to the transaction.
     */

    public void transaction(Work work) {
        run(connection -> {
            connection.setAutoCommit(false);

            try {
                work.run(new Sql(null, connection));
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }

            return null;
        });
    }

    /**
     * Runs work on the bound connection, or on one borrowed from the pool and given back after.
     * @param work What to run.
     * @param <T> What it returns.
     * @return What the work returned.
     */

    private <T> T run(ConnectionWork<T> work) {
        try {
            // Inside a transaction every statement shares its connection.
            if (bound != null) return work.run(bound);

            try (Connection connection = pool.getConnection()) {
                return work.run(connection);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    /**
     * Prepares a statement and binds its values.
     * @param connection The connection.
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

    /**
     * Reads one row of a result.
     * @param <T> What the row reads as.
     */

    public interface RowReader<T> {
        /**
         * @param row The result, at the row.
         * @return The row's value.
         * @throws SQLException When a column cannot be read.
         */

        T read(ResultSet row) throws SQLException;
    }

    /**
     * Statements run inside a transaction.
     */

    public interface Work {
        /**
         * @param sql The runner bound to the transaction.
         * @throws SQLException When a statement fails, which rolls everything back.
         */

        void run(Sql sql) throws SQLException;
    }

    /**
     * Work on one connection.
     * @param <T> What it returns.
     */

    private interface ConnectionWork<T> {
        /**
         * @param connection The connection to run on.
         * @return What the work returns.
         * @throws SQLException When a statement fails.
         */

        T run(Connection connection) throws SQLException;
    }
}
