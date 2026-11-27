package com.minecraftcitiesnetwork.vaultstorage.database;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * Maps {@link UUID} to MariaDB's native UUID column. It writes the canonical string and reads
 * either form: the server returns the column as its 36-character text.
 */
@MappedTypes(UUID.class)
public final class UuidHandler extends BaseTypeHandler<UUID> {

    private static @NotNull UUID fromBytes(byte @NotNull [] bytes) {
        if (bytes.length == 16) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        return UUID.fromString(new String(bytes, StandardCharsets.US_ASCII));
    }

    @Override
    public void setNonNullParameter(@NotNull PreparedStatement ps, int i, @NotNull UUID parameter, @NotNull JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.toString());
    }

    @Override
    public @Nullable UUID getNullableResult(@NotNull ResultSet rs, @NotNull String columnName) throws SQLException {
        byte[] bytes = rs.getBytes(columnName);
        return bytes == null ? null : fromBytes(bytes);
    }

    @Override
    public @Nullable UUID getNullableResult(@NotNull ResultSet rs, int columnIndex) throws SQLException {
        byte[] bytes = rs.getBytes(columnIndex);
        return bytes == null ? null : fromBytes(bytes);
    }

    @Override
    public @Nullable UUID getNullableResult(@NotNull CallableStatement cs, int columnIndex) throws SQLException {
        byte[] bytes = cs.getBytes(columnIndex);
        return bytes == null ? null : fromBytes(bytes);
    }
}
