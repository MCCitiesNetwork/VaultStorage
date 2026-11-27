package com.minecraftcitiesnetwork.vaultstorage.database;

import org.apache.ibatis.session.SqlSession;
import org.jetbrains.annotations.NotNull;

public record MariaSqlSession(@NotNull SqlSession session) implements SqlSessionWrapper {

    @Override
    public @NotNull VaultMapper vaults() {
        return this.session.getMapper(MariaVaultMapper.class);
    }

    @Override
    public @NotNull VaultItemMapper items() {
        return this.session.getMapper(MariaVaultItemMapper.class);
    }

    @Override
    public @NotNull WorldMapper worlds() {
        return this.session.getMapper(MariaWorldMapper.class);
    }

    @Override
    public void close() {
        this.session.close();
    }
}
