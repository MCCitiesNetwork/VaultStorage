package com.minecraftcitiesnetwork.vaultstorage.autovault;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Vendor-neutral contract for the {@code PendingSweep} table. */
public interface PendingSweepMapper {

    /** Stores the sweep, replacing one already waiting for the same region. */
    int upsert(@NotNull PendingSweep sweep);

    @NotNull List<PendingSweep> selectAll();

    int delete(@NotNull UUID worldUuid, @NotNull String regionId);
}
