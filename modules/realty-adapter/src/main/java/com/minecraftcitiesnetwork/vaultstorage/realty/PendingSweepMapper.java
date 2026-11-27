package com.minecraftcitiesnetwork.vaultstorage.realty;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Vendor-neutral contract for the {@code PendingSweep} table. */
public interface PendingSweepMapper {

    /** Stores the sweep, replacing one already waiting for the same region. */
    int upsert(@NotNull PendingSweep sweep);

    @NotNull List<PendingSweep> selectAll();

    /** Deletes this very sweep. One stored for the same region since then is another sweep and stays. */
    int delete(@NotNull PendingSweep sweep);
}
