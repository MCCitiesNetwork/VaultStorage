package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Vendor-neutral contract for the {@code World} table, which a vault's world key refers to. */
public interface WorldMapper {

    /** Records the world, or refreshes its name if it is already there. */
    int upsert(@NotNull UUID uuid, @NotNull String name);
}
