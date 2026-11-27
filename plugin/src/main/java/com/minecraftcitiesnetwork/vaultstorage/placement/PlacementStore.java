package com.minecraftcitiesnetwork.vaultstorage.placement;

import com.minecraftcitiesnetwork.vaultstorage.database.Claim;
import com.minecraftcitiesnetwork.vaultstorage.database.ClaimResult;
import com.minecraftcitiesnetwork.vaultstorage.database.DeleteResult;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** The three writes a placement needs, so a placement can be tested without a database. */
public interface PlacementStore {

    /** Of two callers, exactly one gets the vault. */
    @NotNull ClaimResult claim(@NotNull UUID vault);

    /** Gives the vault back. Only the claim it was taken with does. */
    void release(@NotNull Claim claim);

    /** Deletes the vault the claim holds, and nothing else. */
    @NotNull DeleteResult delete(@NotNull Claim claim);
}
