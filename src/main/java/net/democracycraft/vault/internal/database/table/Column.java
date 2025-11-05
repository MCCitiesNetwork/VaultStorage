package net.democracycraft.vault.internal.database.table;

import java.util.UUID;

/**
 * Simple description of a SQL table column.
 *
 * Holds name, Java type, whether it is a primary key, and if it allows NULL values.
 */
public final class Column<T> {
    private final String name;
    private final Class<T> type;
    private final boolean primaryKey;
    private final boolean nullable;

    public Column(String name, Class<T> type, boolean primaryKey, boolean nullable) {
        this.name = name;
        this.type = type;
        this.primaryKey = primaryKey;
        this.nullable = nullable;
    }

    public String getName() { return name; }
    public Class<T> getType() { return type; }
    public boolean isPrimaryKey() { return primaryKey; }
    public boolean isNullable() { return nullable; }

    private String sqlType() {
        if (type == String.class) {
            return switch (name) {
                case "uuid" -> "VARCHAR(36)";
                case "state" -> "TEXT";
                default -> "VARCHAR(255)";
            };
        }
        if (type == int.class || type == Integer.class) return "INT";
        if (type == long.class || type == Long.class) return "BIGINT";
        if (type == double.class || type == Double.class) return "DOUBLE";
        if (type == float.class || type == Float.class) return "FLOAT";
        if (type == boolean.class || type == Boolean.class) return "BOOLEAN";
        if (type == UUID.class) return "VARCHAR(36)";
        if (type == byte[].class) return "BLOB";
        // Fallback to JSON for complex types
        return "JSON";
    }

    /**
     * @return the column DDL definition fragment used in CREATE TABLE.
     */
    public String definition() {
        String nullStr = nullable ? "" : "NOT NULL";
        // MySQL: make integer PK named 'id' auto-increment
        boolean isAutoInc = primaryKey && "id".equals(name) && (type == int.class || type == Integer.class || type == long.class || type == Long.class);
        String autoStr = isAutoInc ? "AUTO_INCREMENT" : "";
        String pkStr = primaryKey ? "PRIMARY KEY" : "";
        return ("`" + name + "` " + sqlType() + " " + nullStr + " " + autoStr + " " + pkStr).trim();
    }
}
