package com.minecraftcitiesnetwork.vaultstorage.placement;

import com.minecraftcitiesnetwork.vaultstorage.database.StoredVault;
import java.util.UUID;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** What a placement does to the world, so the order of a placement can be tested without one. */
public interface PlacementWorld {

    /** Puts the vault's block at the target and fills it. Refuses a target that is not air. */
    VaultPlacementService.@NotNull Result place(@NotNull StoredVault vault, @NotNull Location target);

    /** Locks the placed block for the vault's owner. */
    void protect(@NotNull Location target, @NotNull UUID owner);
}
