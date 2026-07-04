package net.democracycraft.vault.internal.util.region;

import net.democracycraft.vault.api.region.VaultRegion;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The auto-vault sweep's "should this locked container be vaulted?" decision, in one place for both the block
 * and hanging-entity passes. A container is spared (left alone) when:
 * <ul>
 *   <li>its Bolt owner is allowed — a participant of the swept region, or the new occupant;</li>
 *   <li>it sits inside a region nested within the swept one — a sub-lease governed by its own jurisdiction, so a
 *       title change on the parent must not touch it;</li>
 *   <li>its owner participates in another region overlapping the swept one, where that region covers the spot.</li>
 * </ul>
 * This is the offline/box-based analogue of the participation rules {@code VaultCapturePolicy} evaluates per-block
 * for live captures; it deliberately works from precomputed region boxes rather than per-block region queries.
 */
public final class SweepFilter {

    /** A region overlapping the swept area, paired with its participants (owners ∪ members). */
    public record OwnerZone(BoundingBox box, Set<UUID> participants) {}

    private final Set<UUID> allowed;
    private final List<BoundingBox> exclusions;
    private final List<OwnerZone> ownerZones;

    private SweepFilter(Set<UUID> allowed, List<BoundingBox> exclusions, List<OwnerZone> ownerZones) {
        this.allowed = allowed;
        this.exclusions = exclusions;
        this.ownerZones = ownerZones;
    }

    /**
     * Builds the filter for a sweep of {@code swept}: the allowed set (its participants plus the new
     * {@code initiator}), the nested sub-regions to skip entirely, and the overlapping regions whose participants
     * are spared where their box covers a container. Classifies every other region using the same bounding-box
     * containment/overlap tests as {@link RegionGeometry}.
     */
    public static SweepFilter forSweep(List<VaultRegion> regions, VaultRegion swept, UUID initiator) {
        Set<UUID> allowed = participants(swept);
        allowed.add(initiator);

        BoundingBox sweptBox = swept.boundingBox();
        List<BoundingBox> exclusions = new ArrayList<>();
        List<OwnerZone> ownerZones = new ArrayList<>();
        for (VaultRegion other : regions) {
            if (other.id().equals(swept.id())) continue;
            BoundingBox otherBox = other.boundingBox();
            if (RegionGeometry.containsInclusive(sweptBox, otherBox)) {
                exclusions.add(otherBox);
                continue;
            }
            if (!RegionGeometry.overlapsInclusive(sweptBox, otherBox)) continue;
            Set<UUID> participants = participants(other);
            if (participants.isEmpty()) continue;
            ownerZones.add(new OwnerZone(otherBox, participants));
        }
        return new SweepFilter(allowed, exclusions, ownerZones);
    }

    /** A region's participants: its owners and members combined into a fresh, mutable set for O(1) lookups. */
    private static Set<UUID> participants(VaultRegion region) {
        Set<UUID> combined = new HashSet<>(region.owners());
        combined.addAll(region.members());
        return combined;
    }

    /** Whether a container with the given Bolt owner at (x, y, z) should be vaulted by this sweep. */
    public boolean shouldVault(UUID owner, double x, double y, double z) {
        if (owner == null || allowed.contains(owner)) return false;
        for (BoundingBox exclusion : exclusions) {
            if (RegionGeometry.containsInclusive(exclusion, x, y, z)) return false;
        }
        for (OwnerZone zone : ownerZones) {
            if (zone.participants().contains(owner) && RegionGeometry.containsInclusive(zone.box(), x, y, z)) {
                return false;
            }
        }
        return true;
    }
}
