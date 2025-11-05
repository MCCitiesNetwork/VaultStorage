package net.democracycraft.vault.api.service;

import net.democracycraft.vault.api.region.VaultRegion;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface WorldGuardService {
    /**
     * Returns regions overlapping the bounding box in the given world.
     */
    @NotNull List<VaultRegion> getRegionsAt(@NotNull BoundingBox boundingBox, @NotNull World world);

    /**
     * Returns all regions in the given world (may be cached).
     */
    @NotNull List<VaultRegion> getRegionsIn(@NotNull World world);

    /**
     * Clears the cached regions for the given world.
     */
    void invalidateCache(@NotNull World world);

    /**
     * Reloads all regions for the given world from WorldGuard and refreshes the cache.
     */
    void refreshWorld(@NotNull World world);

    /**
     * Reloads a single region by id from WorldGuard and updates the cache, if present. If the region
     * no longer exists, it will be removed from the cache.
     */
    void refreshRegion(@NotNull World world, @NotNull String regionId);

    /**
     * Removes a region from the cache by id without touching WorldGuard.
     */
    void removeRegion(@NotNull World world, @NotNull String regionId);
}
