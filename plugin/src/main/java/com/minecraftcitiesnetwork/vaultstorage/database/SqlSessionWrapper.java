package com.minecraftcitiesnetwork.vaultstorage.database;

import java.io.Closeable;
import org.apache.ibatis.session.SqlSession;
import org.jetbrains.annotations.NotNull;

/** Typed access to the mappers for one unit of work. Always used in try-with-resources. */
public interface SqlSessionWrapper extends Closeable {

    @NotNull SqlSession session();

    @NotNull VaultMapper vaults();

    @NotNull VaultItemMapper items();

    @NotNull WorldMapper worlds();

    /** A mapper a feature module registered with {@link VaultDatabase#registerMapper}. */
    default <M> @NotNull M mapper(@NotNull Class<M> type) {
        return session().getMapper(type);
    }

    @Override
    void close();
}
