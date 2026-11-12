package com.minecraftcitiesnetwork.vaultstorage.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assertions;
import org.mariadb.jdbc.MariaDbDataSource;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * One MariaDB for every database test, on the image production runs. The container is started
 * once and never stopped; Testcontainers removes it when the JVM exits.
 */
abstract class DatabaseTestBase {

    private static final MariaDBContainer DATABASE = new MariaDBContainer("mariadb:10.11");
    private static final DataSource DATA_SOURCE;

    static {
        DATABASE.start();
        try {
            // UTC, as the plugin's own connections are.
            MariaDbDataSource source = new MariaDbDataSource(DATABASE.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true");
            source.setUser(DATABASE.getUsername());
            source.setPassword(DATABASE.getPassword());
            DATA_SOURCE = source;
        } catch (SQLException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    static DataSource dataSource() {
        return DATA_SOURCE;
    }

    /** Drops every table, so a test starts from an empty database. */
    static void dropEverything() throws SQLException {
        try (Connection connection = DATA_SOURCE.getConnection(); Statement statement = connection.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("SHOW TABLES")) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table : tables) {
                statement.execute("DROP TABLE `" + table + "`");
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    static void migrate() throws SQLException {
        new VaultStorageSchemaMigrator().migrate(DATA_SOURCE, logger());
    }

    /** Runs every statement of a classpath script, split the way the migrator splits it. */
    static void runScript(String resource) throws SQLException, IOException {
        String script;
        try (InputStream stream = DatabaseTestBase.class.getClassLoader().getResourceAsStream(resource)) {
            Assertions.assertNotNull(stream, resource + " is not on the classpath");
            script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = DATA_SOURCE.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : new VaultStorageSchemaMigrator().statements(script)) {
                statement.execute(sql);
            }
        }
    }

    static long queryLong(String sql) throws SQLException {
        try (Connection connection = DATA_SOURCE.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            Assertions.assertTrue(rows.next(), "no row for: " + sql);
            return rows.getLong(1);
        }
    }

    static Logger logger() {
        return Logger.getLogger("vaultstorage-test");
    }
}
