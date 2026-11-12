package net.democracycraft.vault.internal.util.region;

import org.bukkit.util.BoundingBox;

/**
 * Inclusive axis-aligned bounding-box tests for WorldGuard regions. Inclusive on all bounds because a
 * WorldGuard region's maximum point is an inclusive block coordinate — unlike {@link BoundingBox}'s own
 * {@code contains}/{@code overlaps}, which are exclusive on the max edge.
 */
public final class RegionGeometry {

    private RegionGeometry() {}

    /** True if the point lies within the box, inclusive on all six bounds. */
    public static boolean containsInclusive(BoundingBox b, double x, double y, double z) {
        return x >= b.getMinX() && x <= b.getMaxX()
                && y >= b.getMinY() && y <= b.getMaxY()
                && z >= b.getMinZ() && z <= b.getMaxZ();
    }

    /** True if {@code outer} fully encloses {@code inner}, inclusive on all bounds. */
    public static boolean containsInclusive(BoundingBox outer, BoundingBox inner) {
        return outer.getMinX() <= inner.getMinX() && outer.getMaxX() >= inner.getMaxX()
                && outer.getMinY() <= inner.getMinY() && outer.getMaxY() >= inner.getMaxY()
                && outer.getMinZ() <= inner.getMinZ() && outer.getMaxZ() >= inner.getMaxZ();
    }

    /** True if the two boxes intersect or merely touch (inclusive on the max edge). */
    public static boolean overlapsInclusive(BoundingBox a, BoundingBox b) {
        return a.getMinX() <= b.getMaxX() && a.getMaxX() >= b.getMinX()
                && a.getMinY() <= b.getMaxY() && a.getMaxY() >= b.getMinY()
                && a.getMinZ() <= b.getMaxZ() && a.getMaxZ() >= b.getMinZ();
    }
}
