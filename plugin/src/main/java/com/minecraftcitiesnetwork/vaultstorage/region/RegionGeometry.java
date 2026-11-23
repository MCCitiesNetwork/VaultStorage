package com.minecraftcitiesnetwork.vaultstorage.region;

import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

/**
 * Bounding-box tests for WorldGuard regions, inclusive on every bound: a region's maximum
 * point is a block that belongs to it, unlike {@link BoundingBox}'s own tests.
 */
public final class RegionGeometry {

    private RegionGeometry() {
    }

    /**
     * True if {@code inner} lies inside {@code outer} and is not the same box. Two regions with
     * the same bounds are neither nested in the other.
     */
    public static boolean nestedInside(@NotNull BoundingBox outer, @NotNull BoundingBox inner) {
        return outer.getMinX() <= inner.getMinX() && outer.getMaxX() >= inner.getMaxX()
                && outer.getMinY() <= inner.getMinY() && outer.getMaxY() >= inner.getMaxY()
                && outer.getMinZ() <= inner.getMinZ() && outer.getMaxZ() >= inner.getMaxZ()
                && !outer.equals(inner);
    }
}
