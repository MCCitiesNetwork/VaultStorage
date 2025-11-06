package net.democracycraft.vault.api.service;

import net.democracycraft.vault.api.region.VaultRegion;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Service for accessing WorldGuard regions.
 *
 * This API performs live lookups against WorldGuard's RegionManager on every call
 * and does not maintain any internal cache.
 */
public interface WorldGuardService {
    /**
     * Returns regions overlapping the bounding box in the given world.
     * The result is fetched live from WorldGuard.
     */
    @NotNull List<VaultRegion> getRegionsAt(@NotNull BoundingBox boundingBox, @NotNull World world);

    /**
     * Returns all regions in the given world.
     * The result is fetched live from WorldGuard.
     */
    @NotNull List<VaultRegion> getRegionsIn(@NotNull World world);
}
