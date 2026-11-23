package com.minecraftcitiesnetwork.vaultstorage.realty;

import com.minecraftcitiesnetwork.vaultstorage.region.RegionGeometry;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Whether a sweep vaults something a previous occupant left locked. One rule for every pass:
 * locked blocks, hanging entities and shops.
 *
 * <p>It asks which regions are at the thing's position, so a region counts by its real shape:
 * the corner of a bounding box that an L-shaped plot leaves out is not the plot. Only "nested
 * inside the swept region" is still decided by bounding boxes.
 */
public final class SweepFilter {

    /** The regions at a block, by their real shape. */
    @FunctionalInterface
    public interface RegionsAt {
        @NotNull List<VaultRegion> at(int x, int y, int z);
    }

    private final @NotNull String sweptId;
    private final @NotNull BoundingBox sweptBox;
    private final @NotNull Set<UUID> allowed;
    private final @NotNull RegionsAt regionsAt;

    private SweepFilter(@NotNull String sweptId, @NotNull BoundingBox sweptBox, @NotNull Set<UUID> allowed,
                        @NotNull RegionsAt regionsAt) {
        this.sweptId = sweptId;
        this.sweptBox = sweptBox;
        this.allowed = allowed;
        this.regionsAt = regionsAt;
    }

    /**
     * @param swept     the region that changed hands
     * @param initiator its new occupant
     * @param regionsAt asked once for each candidate
     */
    public static @NotNull SweepFilter forSweep(@NotNull VaultRegion swept, @NotNull UUID initiator,
                                                @NotNull RegionsAt regionsAt) {
        Set<UUID> allowed = new HashSet<>(swept.owners());
        allowed.addAll(swept.members());
        allowed.add(initiator);
        return new SweepFilter(swept.id(), swept.boundingBox(), allowed, regionsAt);
    }

    /**
     * A thing with this owner at this position is vaulted when all of these hold:
     * <ul>
     *   <li>it has an owner, who is neither the new occupant nor an owner or member of the swept region;</li>
     *   <li>the swept region is among the regions at its position;</li>
     *   <li>no region there is nested inside the swept region: a nested region is someone's own;</li>
     *   <li>its owner has no part in any other region there.</li>
     * </ul>
     */
    public boolean shouldVault(@Nullable UUID owner, double x, double y, double z) {
        if (owner == null || this.allowed.contains(owner)) {
            return false;
        }
        boolean inSwept = false;
        for (VaultRegion region : this.regionsAt.at((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))) {
            if (region.id().equals(this.sweptId)) {
                inSwept = true;
            } else if (RegionGeometry.nestedInside(this.sweptBox, region.boundingBox())
                    || region.isPartOfRegion(owner)) {
                return false;
            }
        }
        return inSwept;
    }
}
