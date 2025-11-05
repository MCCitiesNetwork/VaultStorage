package net.democracycraft.vault.internal.database.table;

import com.google.gson.Gson;
import net.democracycraft.vault.internal.database.MySQLManager;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reflection-driven table helper that can auto-generate DDL and perform simple CRUD operations
 * for plain Java model classes.
 */
public class AutoTable<T> extends AbstractTable {

    protected final Class<T> clazz;
    protected final String tableName;
    protected final String primaryKey;

    protected final List<Column<?>> columns;
    protected final Gson gson;

    public AutoTable(MySQLManager mysqlManager, Class<T> clazz, String tableName) {
        this(mysqlManager, clazz, tableName, "uuid");
    }

    public AutoTable(MySQLManager mysqlManager, Class<T> clazz, String tableName, String primaryKey) {
        super(mysqlManager);
        this.clazz = Objects.requireNonNull(clazz, "clazz");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.primaryKey = Objects.requireNonNull(primaryKey, "primaryKey");
        this.gson = mysqlManager.gson;
        this.columns = computeColumns(clazz, primaryKey);
    }

    @Override
    public String tableName() { return tableName; }

    @Override
    public void createTable() {
        String columnsSql = columns.stream().map(Column::definition).collect(Collectors.joining(",\n"));
        String sql = "CREATE TABLE IF NOT EXISTS `" + tableName + "` (\n" + columnsSql + "\n);";
        mysql.withConnection(conn -> {
            try (var st = conn.createStatement()) {
                st.execute(sql);
            }
            // Add simple secondary indexes to speed up lookups (ignore failures on existing)
            for (Column<?> col : columns) {
                if (col.isPrimaryKey()) continue;
                String idx = "CREATE INDEX `" + col.getName() + "_idx` ON `" + tableName + "`(`" + col.getName() + "`);";
                try (var st = conn.createStatement()) {
                    st.execute(idx);
                } catch (SQLException ignored) {}
            }
            return null;
        });
    }

    /** Inserts or updates by primary key using MySQL's duplicate key update. */
    public void insertOrUpdate(T obj) {
        List<Field> fields = modelFields();
        String names = fields.stream().map(f -> "`" + f.getName() + "`").collect(Collectors.joining(","));
        String placeholders = fields.stream().map(f -> "?").collect(Collectors.joining(","));
        String updates = fields.stream().filter(f -> !f.getName().equals(primaryKey))
                .map(f -> "`" + f.getName() + "` = VALUES(`" + f.getName() + "`)")
                .collect(Collectors.joining(","));
        String sql = "INSERT INTO `" + tableName + "` (" + names + ") VALUES (" + placeholders + ") ON DUPLICATE KEY UPDATE " + updates + ";";
        mysql.runAsync(() -> executeSingle(sql, fields, obj));
    }

    /** Batch insert/update using a single prepared statement. */
    public void insertBatch(Collection<T> objs) {
        if (objs == null || objs.isEmpty()) return;
        List<Field> fields = modelFields();
        String names = fields.stream().map(f -> "`" + f.getName() + "`").collect(Collectors.joining(","));
        String placeholders = fields.stream().map(f -> "?").collect(Collectors.joining(","));
        String updates = fields.stream().filter(f -> !f.getName().equals(primaryKey))
                .map(f -> "`" + f.getName() + "` = VALUES(`" + f.getName() + "`)")
                .collect(Collectors.joining(","));
        String sql = "INSERT INTO `" + tableName + "` (" + names + ") VALUES (" + placeholders + ") ON DUPLICATE KEY UPDATE " + updates + ";";
        mysql.runAsync(() -> mysql.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (T obj : objs) {
                    for (int i = 0; i < fields.size(); i++) {
                        Field f = fields.get(i);
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        ps.setObject(i + 1, serializeValue(v));
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        }));
    }

    /** Insert a new row excluding the primary key field, returning an auto-generated integer key if available. */
    public Integer insertReturningIntKey(T obj) {
        List<Field> fields = modelFields().stream().filter(f -> !f.getName().equals(primaryKey)).collect(Collectors.toList());
        String names = fields.stream().map(f -> "`" + f.getName() + "`").collect(Collectors.joining(","));
        String placeholders = fields.stream().map(f -> "?").collect(Collectors.joining(","));
        String sql = "INSERT INTO `" + tableName + "` (" + names + ") VALUES (" + placeholders + ");";
        return mysql.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                for (int i = 0; i < fields.size(); i++) {
                    Field f = fields.get(i);
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    ps.setObject(i + 1, serializeValue(v));
                }
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) return keys.getInt(1);
                }
                return null;
            }
        });
    }

    /** Simple insert excluding primary key. */
    public void insertNonPk(T obj) {
        List<Field> fields = modelFields().stream().filter(f -> !f.getName().equals(primaryKey)).collect(Collectors.toList());
        String names = fields.stream().map(f -> "`" + f.getName() + "`").collect(Collectors.joining(","));
        String placeholders = fields.stream().map(f -> "?").collect(Collectors.joining(","));
        String sql = "INSERT INTO `" + tableName + "` (" + names + ") VALUES (" + placeholders + ");";
        mysql.runAsync(() -> executeSingle(sql, fields, obj));
    }

    /** Synchronous variant of insertOrUpdate. */
    public void insertOrUpdateSync(T obj) {
        List<Field> fields = modelFields();
        String names = fields.stream().map(f -> "`" + f.getName() + "`").collect(Collectors.joining(","));
        String placeholders = fields.stream().map(f -> "?").collect(Collectors.joining(","));
        String updates = fields.stream().filter(f -> !f.getName().equals(primaryKey))
                .map(f -> "`" + f.getName() + "` = VALUES(`" + f.getName() + "`)")
                .collect(Collectors.joining(","));
        String sql = "INSERT INTO `" + tableName + "` (" + names + ") VALUES (" + placeholders + ") ON DUPLICATE KEY UPDATE " + updates + ";";
        executeSingle(sql, fields, obj);
    }

    /** Synchronous batch insert/update. */
    public void insertBatchSync(Collection<T> objs) {
        if (objs == null || objs.isEmpty()) return;
        List<Field> fields = modelFields();
        String names = fields.stream().map(f -> "`" + f.getName() + "`").collect(Collectors.joining(","));
        String placeholders = fields.stream().map(f -> "?").collect(Collectors.joining(","));
        String updates = fields.stream().filter(f -> !f.getName().equals(primaryKey))
                .map(f -> "`" + f.getName() + "` = VALUES(`" + f.getName() + "`)")
                .collect(Collectors.joining(","));
        String sql = "INSERT INTO `" + tableName + "` (" + names + ") VALUES (" + placeholders + ") ON DUPLICATE KEY UPDATE " + updates + ";";
        mysql.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (T obj : objs) {
                    for (int i = 0; i < fields.size(); i++) {
                        Field f = fields.get(i);
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        ps.setObject(i + 1, serializeValue(v));
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /** Synchronous simple insert excluding primary key. */
    public boolean insertNonPkSync(T obj) {
        List<Field> fields = modelFields().stream().filter(f -> !f.getName().equals(primaryKey)).collect(Collectors.toList());
        String names = fields.stream().map(f -> "`" + f.getName() + "`").collect(Collectors.joining(","));
        String placeholders = fields.stream().map(f -> "?").collect(Collectors.joining(","));
        String sql = "INSERT INTO `" + tableName + "` (" + names + ") VALUES (" + placeholders + ");";
        return mysql.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < fields.size(); i++) {
                    Field f = fields.get(i);
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    ps.setObject(i + 1, serializeValue(v));
                }
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Finds a single record by an indexed field or primary key. */
    public T findBy(String field, Object value) {
        ensureFieldExists(field);
        String sql = "SELECT * FROM `" + tableName + "` WHERE `" + field + "` = ? LIMIT 1;";
        return mysql.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(sql)) {
                st.setObject(1, (value instanceof UUID) ? value.toString() : value);
                try (ResultSet rs = st.executeQuery()) {
                    if (rs.next()) return buildFromResultSet(rs);
                    return null;
                }
            }
        });
    }

    /** Returns all rows where field equals value, ordered if requested. */
    public List<T> findAllBy(String field, Object value, String orderBy) {
        ensureFieldExists(field);
        StringBuilder sql = new StringBuilder("SELECT * FROM `" + tableName + "` WHERE `" + field + "` = ?");
        if (orderBy != null) { ensureFieldExists(orderBy); sql.append(" ORDER BY `").append(orderBy).append("`"); }
        sql.append(";");
        return mysql.withConnection(conn -> {
            List<T> out = new ArrayList<>();
            try (PreparedStatement st = conn.prepareStatement(sql.toString())) {
                st.setObject(1, (value instanceof UUID) ? value.toString() : value);
                try (ResultSet rs = st.executeQuery()) { while (rs.next()) out.add(buildFromResultSet(rs)); }
            }
            return out;
        });
    }

    /** Returns all rows matching equality conditions in 'where', ordered if requested. */
    public List<T> findAllByMany(Map<String, Object> where, String orderBy) {
        StringBuilder sql = new StringBuilder("SELECT * FROM `" + tableName + "`");
        List<String> conds = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (where != null && !where.isEmpty()) {
            sql.append(" WHERE ");
            for (Map.Entry<String, Object> e : where.entrySet()) {
                ensureFieldExists(e.getKey());
                conds.add("`" + e.getKey() + "` = ?");
                params.add(e.getValue());
            }
            sql.append(String.join(" AND ", conds));
        }
        if (orderBy != null) { ensureFieldExists(orderBy); sql.append(" ORDER BY `").append(orderBy).append("`"); }
        sql.append(";");
        return mysql.withConnection(conn -> {
            List<T> out = new ArrayList<>();
            try (PreparedStatement st = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    Object v = params.get(i);
                    st.setObject(i + 1, (v instanceof UUID) ? v.toString() : v);
                }
                try (ResultSet rs = st.executeQuery()) { while (rs.next()) out.add(buildFromResultSet(rs)); }
            }
            return out;
        });
    }

    /** Returns all rows with an optional LIMIT. */
    public List<T> getAll(Integer limit) {
        String base = "SELECT * FROM `" + tableName + "`";
        String sql = (limit != null) ? base + " LIMIT " + limit : base;
        return queryList(sql);
    }

    /** Returns a page using LIMIT/OFFSET. */
    public List<T> getPage(int offset, int pageSize) {
        String sql = "SELECT * FROM `" + tableName + "` LIMIT " + pageSize + " OFFSET " + offset + ";";
        return queryList(sql);
    }

    /** Deletes a row by primary key. */
    public void deleteById(Object id) {
        String sql = "DELETE FROM `" + tableName + "` WHERE `" + primaryKey + "` = ?;";
        mysql.runAsync(() -> mysql.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(sql)) {
                st.setObject(1, (id instanceof UUID) ? id.toString() : id);
                st.executeUpdate();
            }
            return null;
        }));
    }

    /** Deletes rows matching equality conditions. */
    public void deleteWhere(Map<String, Object> where) {
        if (where == null || where.isEmpty()) return;
        StringBuilder sql = new StringBuilder("DELETE FROM `" + tableName + "` WHERE ");
        List<Object> params = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> e : where.entrySet()) {
            ensureFieldExists(e.getKey());
            if (!first) sql.append(" AND ");
            first = false;
            sql.append("`").append(e.getKey()).append("` = ?");
            params.add(e.getValue());
        }
        sql.append(";");
        mysql.runAsync(() -> mysql.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    Object v = params.get(i);
                    st.setObject(i + 1, (v instanceof UUID) ? v.toString() : v);
                }
                st.executeUpdate();
            }
            return null;
        }));
    }

    /** Synchronous delete with where clause; returns affected rows. */
    public int deleteWhereSync(Map<String, Object> where) {
        if (where == null || where.isEmpty()) return 0;
        StringBuilder sql = new StringBuilder("DELETE FROM `" + tableName + "` WHERE ");
        List<Object> params = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> e : where.entrySet()) {
            ensureFieldExists(e.getKey());
            if (!first) sql.append(" AND ");
            first = false;
            sql.append("`").append(e.getKey()).append("` = ?");
            params.add(e.getValue());
        }
        sql.append(";");
        return mysql.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    Object v = params.get(i);
                    st.setObject(i + 1, (v instanceof UUID) ? v.toString() : v);
                }
                return st.executeUpdate();
            }
        });
    }

    // --- internals ---

    private List<T> queryList(String sql) {
        return mysql.withConnection(conn -> {
            List<T> out = new ArrayList<>();
            try (PreparedStatement st = conn.prepareStatement(sql);
                 ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    T row = buildFromResultSet(rs);
                    if (row != null) out.add(row);
                }
            }
            return out;
        });
    }

    private T buildFromResultSet(ResultSet rs) throws SQLException {
        try {
            T instance = clazz.getDeclaredConstructor().newInstance();
            for (Field f : modelFields()) {
                f.setAccessible(true);
                String name = f.getName();
                Object raw = rs.getObject(name);
                if (raw == null) { f.set(instance, null); continue; }
                Class<?> target = f.getType();

                Object value;
                if (target == UUID.class) {
                    value = UUID.fromString(raw.toString());
                } else if (target == String.class) {
                    value = rs.getString(name);
                } else if (isPrimitiveOrBox(target)) {
                    // Let JDBC perform primitive conversions
                    value = raw;
                } else if (raw instanceof byte[] && target == byte[].class) {
                    value = raw;
                } else {
                    // JSON-backed field
                    String json = rs.getString(name);
                    Type t = f.getGenericType();
                    value = gson.fromJson(json, t);
                }
                f.set(instance, value);
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to hydrate " + clazz.getSimpleName(), e);
        }
    }

    private Object serializeValue(Object value) {
        if (value == null) return null;
        if (value instanceof UUID uuid) return uuid.toString();
        if (value instanceof byte[]) return value;
        if (value instanceof CharSequence) return value.toString();
        if (isPrimitiveOrBox(value.getClass())) return value;
        if (value instanceof Collection<?> || value instanceof Map<?,?>) return gson.toJson(value);
        // Complex types -> JSON
        return gson.toJson(value);
    }

    private boolean isPrimitiveOrBox(Class<?> c) {
        return c.isPrimitive() ||
                c == Integer.class || c == Long.class || c == Double.class || c == Float.class ||
                c == Boolean.class || c == Character.class || c == Short.class || c == Byte.class ||
                c == String.class;
    }

    private List<Field> modelFields() {
        List<Field> fields = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || f.isSynthetic()) continue;
                fields.add(f);
            }
            c = c.getSuperclass();
        }
        // Keep deterministic order
        fields.sort(Comparator.comparing(Field::getName));
        return fields;
    }

    private void ensureFieldExists(String name) {
        for (Column<?> c : columns) {
            if (c.getName().equalsIgnoreCase(name)) return;
        }
        throw new IllegalArgumentException("Invalid field name: " + name);
    }

    private static List<Column<?>> computeColumns(Class<?> clazz, String primaryKey) {
        List<Column<?>> cols = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || f.isSynthetic()) continue;
                @SuppressWarnings("unchecked")
                Class<Object> type = (Class<Object>) f.getType();
                boolean nullable = !type.isPrimitive();
                cols.add(new Column<>(f.getName(), type, f.getName().equals(primaryKey), nullable));
            }
            c = c.getSuperclass();
        }
        // Deterministic order by field name
        cols.sort(Comparator.comparing(Column::getName));
        return cols;
    }

    /** Executes a single prepared statement for the given fields and object. */
    private void executeSingle(String sql, List<Field> fields, T obj) {
        mysql.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < fields.size(); i++) {
                    Field f = fields.get(i);
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    ps.setObject(i + 1, serializeValue(v));
                }
                ps.executeUpdate();
            }
            return null;
        });
    }
}
