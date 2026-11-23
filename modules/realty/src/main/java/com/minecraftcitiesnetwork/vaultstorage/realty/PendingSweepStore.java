package com.minecraftcitiesnetwork.vaultstorage.realty;

import com.minecraftcitiesnetwork.vaultstorage.database.SqlSessionWrapper;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultDatabase;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * The sweeps waiting to run, kept in the module's own table through the host's connection pool.
 * Every method blocks on the database.
 */
public final class PendingSweepStore {

    private final @NotNull VaultDatabase database;

    public PendingSweepStore(@NotNull VaultDatabase database) {
        this.database = database;
    }

    /** Creates the table if it is missing and makes the mapper known to the host's MyBatis. */
    public void migrate(@NotNull Logger logger) throws SQLException {
        new RealtySchemaMigrator().migrate(this.database.dataSource(), logger);
        this.database.registerMapper(MariaPendingSweepMapper.class);
    }

    public void save(@NotNull PendingSweep sweep) {
        try (SqlSessionWrapper wrapper = this.database.openSession(true)) {
            wrapper.mapper(MariaPendingSweepMapper.class).upsert(sweep);
        }
    }

    public @NotNull List<PendingSweep> all() {
        try (SqlSessionWrapper wrapper = this.database.openSession(true)) {
            return wrapper.mapper(MariaPendingSweepMapper.class).selectAll();
        }
    }

    public void delete(@NotNull UUID worldUuid, @NotNull String regionId) {
        try (SqlSessionWrapper wrapper = this.database.openSession(true)) {
            wrapper.mapper(MariaPendingSweepMapper.class).delete(worldUuid, regionId);
        }
    }
}
