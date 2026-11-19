package com.minecraftcitiesnetwork.vaultstorage.region;

import com.minecraftcitiesnetwork.vaultstorage.Service;

import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Service for accessing WorldGuard regions.
 *<p>
 * This API performs live lookups against WorldGuard's RegionManager on every call
 * and does not maintain any internal cache.
 */
public interface WorldGuardService extends Service {
    /** The regions that contain the block, by their real shape. */
    @NotNull List<VaultRegion> getRegionsAt(@NotNull Block block);

    /** Every region in the world. Builds them all: for listing regions, not for finding one. */
    @NotNull List<VaultRegion> getRegionsIn(@NotNull World world);

    /** The region with this id, or null. Does not build the others. */
    @Nullable VaultRegion getRegionById(@NotNull String id, @NotNull World world);


    /**
     * Whether the block is inside the region, by the region's real shape. A region's bounding
     * box also covers land that is not the region's when the region is not a cuboid.
     */
    default boolean isInside(@NotNull String regionId, @NotNull Block block) {
        return getRegionsAt(block).stream().anyMatch(region -> region.id().equalsIgnoreCase(regionId));
    }
}
