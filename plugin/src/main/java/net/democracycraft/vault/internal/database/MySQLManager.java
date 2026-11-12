package net.democracycraft.vault.internal.database;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.internal.util.config.ConfigPaths;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Thin MySQL manager for obtaining JDBC connections and running small tasks asynchronously.
 *
 * Contract:
 * - Reads configuration keys from plugin config: mysql.host, mysql.port, mysql.database, mysql.user, mysql.password, mysql.useSSL.
 * - Provides a lazily created single JDBC connection (guarded by a lock for multi-threaded access).
 * - Exposes a Gson instance for JSON serialization used by table helpers.
 * - Offers withConnection utility to safely execute code with the current connection.
 * - Offers withTransaction utility for atomic multi-statement operations.
 */
public class MySQLManager {

    final VaultStoragePlugin plugin;

    /** A Gson instance with null serialization enabled. */
    public final Gson gson = new GsonBuilder().serializeNulls().create();

    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;
    private final boolean useSSL;

    private volatile Connection connection;
    private final Object connectionLock = new Object();

    public MySQLManager(VaultStoragePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        var cfg = plugin.getConfig();
        this.host = Objects.requireNonNull(cfg.getString(ConfigPaths.MYSQL_HOST.getPath()), ConfigPaths.MYSQL_HOST.getPath());
        this.port = cfg.getInt(ConfigPaths.MYSQL_PORT.getPath());
        this.database = Objects.requireNonNull(cfg.getString(ConfigPaths.MYSQL_DATABASE.getPath()), ConfigPaths.MYSQL_DATABASE.getPath());
        this.user = Objects.requireNonNull(cfg.getString(ConfigPaths.MYSQL_USER.getPath()), ConfigPaths.MYSQL_USER.getPath());
        this.password = Objects.requireNonNull(cfg.getString(ConfigPaths.MYSQL_PASSWORD.getPath()), ConfigPaths.MYSQL_PASSWORD.getPath());
        this.useSSL = cfg.getBoolean(ConfigPaths.MYSQL_USE_SSL.getPath());
    }

    /** Ensures that the target database exists; creates it if missing. */
    public void ensureDatabaseExists() {
        String serverUrl = "jdbc:mysql://" + host + ":" + port + "/?useSSL=" + useSSL + "&autoReconnect=true&characterEncoding=UTF-8&serverTimezone=UTC";
        try (Connection c = DriverManager.getConnection(serverUrl, user, password)) {
            try (var st = c.createStatement()) {
                st.execute("CREATE DATABASE IF NOT EXISTS `" + database + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to ensure database exists. Check credentials and privileges.", ex);
        }
    }

    /**
     * Opens a connection if none exists or it is closed.
     */
    public void connect() {
        try {
            if (connection != null && !connection.isClosed()) return;
        } catch (SQLException ignored) {}

        final String url = "jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=" + useSSL + "&autoReconnect=true&characterEncoding=UTF-8&serverTimezone=UTC";
        try {
            connection = DriverManager.getConnection(url, user, password);
            plugin.getLogger().info("Connected to MySQL");
        } catch (SQLException ex) {
            connection = null; // ensure null on failure
            plugin.getLogger().log(Level.SEVERE, "Failed to connect to MySQL (" + url + ") as '" + user + "'.", ex);
        }
    }

    /** Shorthand to ensure DB and connect. */
    public void setupDatabase() {
        ensureDatabaseExists();
        connect();
    }

    /**
     * Closes the active connection, if any.
     */
    public void disconnect() {
        try {
            if (connection != null) connection.close();
            plugin.getLogger().info("Disconnected from MySQL");
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to disconnect MySQL", ex);
        }
    }

    /**
     * Guaranteed to return a live connection; reconnects as needed and throws if unavailable.
     */
    public Connection getConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                connect();
            }
        } catch (SQLException e) {
            connect();
        }
        if (connection == null) {
            throw new IllegalStateException("MySQL connection is not available. Verify mysql.host/port/database/user/password, server reachability, and privileges.");
        }
        return connection;
    }

    /**
     * Thread-safe execution with a JDBC connection, returning a value.
     */
    public <R> R withConnection(IOFunction<Connection, R> fn) {
        synchronized (connectionLock) {
            Connection conn;
            try {
                conn = getConnection();
            } catch (RuntimeException e) {
                // Re-throw with clearer context for callers
                throw new RuntimeException("No MySQL connection available for operation.", e);
            }
            try {
                return fn.apply(conn);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Executes the provided function inside a transaction with connection-level synchronization.
     * Auto-commits are disabled for the duration; on any exception, a rollback is issued.
     *
     * @param fn work to execute within a single transaction
     * @return function result
     */
    public <R> R withTransaction(IOFunction<Connection, R> fn) {
        synchronized (connectionLock) {
            Connection conn = getConnection();
            boolean prevAutoCommit;
            try {
                prevAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    R result = fn.apply(conn);
                    conn.commit();
                    return result;
                } catch (Exception e) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                    throw new RuntimeException(e);
                } finally {
                    try { conn.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) {}
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to manage transaction state", e);
            }
        }
    }

    /**
     * Runs a task asynchronously on the Bukkit scheduler (useful for DB writes).
     */
    public void runAsync(Runnable task) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }

    /**
     * Simple ping to validate the connection is healthy.
     *
     * @return true if the connection responds to a small query
     */
    public boolean ping() {
        try {
            return withConnection(conn -> {
                try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT 1")) {
                    return rs.next();
                }
            });
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * IO-like functional interface allowing lambdas that throw checked exceptions.
     */
    @FunctionalInterface
    public interface IOFunction<T, R> {
        R apply(T t) throws Exception;
    }
}
