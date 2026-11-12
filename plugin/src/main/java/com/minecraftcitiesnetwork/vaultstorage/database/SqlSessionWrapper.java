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

    @Override
    void close();
}
