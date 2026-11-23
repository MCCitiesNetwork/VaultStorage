package com.minecraftcitiesnetwork.vaultstorage.realty;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.realty.SweepFilter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.Protection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * In-memory filter over a region's Bolt protections. Returns the locked blocks the {@link SweepFilter} says
 * should be vaulted, using only Bolt's stored coordinates/owner — no block reads or chunk loads, so it never
 * blocks the main thread. Callers load chunks asynchronously when vaulting the results.
 */
public final class OfflineRegionScanner {

    private OfflineRegionScanner() {}

    /** A locked container flagged for vaulting: its coordinates and Bolt owner. */
    public record DisplacedContainer(int x, int y, int z, @NotNull UUID owner) {}

    /** Filters the region's Bolt protections to displaced containers, or {@code null} if services are unavailable. */
    public static @Nullable List<DisplacedContainer> scan(@Nullable BoltService boltService, @NotNull UUID worldId, @NotNull BoundingBox boundingBox,
                                                @NotNull SweepFilter filter) {
        World world = Bukkit.getWorld(worldId);
        if (world == null || boltService == null) {
            return null;
        }

        Collection<Protection> protections = boltService.getProtections(boundingBox, world);
        List<DisplacedContainer> displaced = new ArrayList<>();
        if (protections != null) {
            for (Protection protection : protections) {
                if (!(protection instanceof BlockProtection bp)) continue;
                if (bp.getY() < world.getMinHeight() || bp.getY() >= world.getMaxHeight()) continue;
                UUID owner = bp.getOwner();
                if (!filter.shouldVault(owner, bp.getX(), bp.getY(), bp.getZ())) continue;
                displaced.add(new DisplacedContainer(bp.getX(), bp.getY(), bp.getZ(), owner));
            }
        }
        return displaced;
    }
}
