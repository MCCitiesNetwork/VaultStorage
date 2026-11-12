package net.democracycraft.vault.internal.database.table;

import net.democracycraft.vault.internal.database.MySQLManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Base class for SQL table helpers.
 *
 * Provides a connection accessor and a default dropTable implementation.
 */
public abstract class AbstractTable {
    protected final MySQLManager mysql;

    protected AbstractTable(MySQLManager mysql) {
        this.mysql = mysql;
    }

    /** @return the live JDBC connection (reconnecting as needed). */
    protected Connection connection() { return mysql.getConnection(); }

    /** Creates the table if it does not exist. */
    public abstract void createTable();

    /** Drops the table if it exists. */
    public void dropTable() {
        try (Statement st = connection().createStatement()) {
            st.execute("DROP TABLE IF EXISTS `" + tableName() + "`");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** @return the table name. */
    public abstract String tableName();
}
