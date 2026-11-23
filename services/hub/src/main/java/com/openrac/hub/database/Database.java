package com.openrac.hub.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import com.openrac.utils.logger.Log;

import javax.sql.DataSource;

/**
 * The hub's PostgreSQL connection pool. Opening it brings the schema up to date with the
 * migrations under db/migration before anything reads it.
 */

public class Database implements AutoCloseable {
    private final HikariDataSource pool;

    /**
     * Opens the pool and runs every pending migration.
     * @param url The JDBC URL (jdbc:postgresql://host:5432/openrac).
     * @param user The role the hub logs in as.
     * @param password That role's password.
     * @param size The most connections the pool holds.
     */

    public Database(String url, String user, String password, int size) {
        HikariConfig config = new HikariConfig();

        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(Math.min(2, size));
        config.setPoolName("openrac-hub");

        // Fail a login fast rather than queue it behind a dead database.
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(3_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);
        config.setKeepaliveTime(30_000);

        pool = new HikariDataSource(config);

        int applied = Flyway.configure().dataSource(pool).locations("classpath:db/migration").load().migrate().migrationsExecuted;

        Log.notice("Connected to PostgreSQL, " + applied + " migrations applied.");
    }

    /**
     * Closes every pooled connection.
     */

    @Override
    public void close() {
        pool.close();
    }

    /**
     * @return The pool, for the stores that query it.
     */

    public DataSource getPool() {
        return pool;
    }
}
