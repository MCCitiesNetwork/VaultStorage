package com.minecraftcitiesnetwork.vaultstorage.region;


import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Service for accessing WorldGuard regions.
 *<p>
 * This API performs live lookups against WorldGuard's RegionManager on every call
 * and does not maintain any internal cache.
 */
public interface WorldGuardService {
    /** The regions that contain the block, by their real shape. */
    @NotNull List<VaultRegion> getRegionsAt(@NotNull Block block);

    /**
     * The ids of the world's regions whose id contains the text, sorted. No region is built.
     *
     * @param nameContains any case; empty for every region
     * @param partOf       when not null, only regions this player owns or is a member of,
     *                     through a parent region too
     */
    @NotNull List<String> regionIds(@NotNull World world, @NotNull String nameContains, @Nullable UUID partOf);

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
