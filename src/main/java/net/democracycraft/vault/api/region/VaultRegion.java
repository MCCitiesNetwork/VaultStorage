package net.democracycraft.vault.api.region;

import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Immutable view of a WorldGuard region used by the plugin.
 * Implementations should be cheap to construct from live WorldGuard data.
 */
public interface VaultRegion {
    /** @return region id (stable, lowercase recommended) */
    @NotNull String id();

    /** @return list of member UUIDs (may be empty, never null) */
    @NotNull List<UUID> members();

    /** @return list of owner UUIDs (may be empty, never null) */
    @NotNull List<UUID> owners();

    /** @return axis-aligned bounding box of the region (never null) */
    @NotNull BoundingBox boundingBox();

    /**
     * Checks if the given player UUID is a member of this region.
     */
    boolean isMember(@NotNull UUID playerUuid);

    /**
     * Checks if the given player UUID is an owner of this region.
     */
    boolean isOwner(@NotNull UUID playerUuid);

    /**
     * Checks whether a point lies inside this region using its bounding box.
     * Note: This is an AABB check and may include points outside polygonal shapes
     * if the underlying region is non-rectangular; acceptable for fast filtering.
     */
    default boolean contains(double x, double y, double z) {
        return boundingBox().contains(x, y, z);
    }
}
