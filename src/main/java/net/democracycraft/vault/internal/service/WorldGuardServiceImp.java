package net.democracycraft.vault.internal.service;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import net.democracycraft.vault.api.region.VaultRegion;
import net.democracycraft.vault.api.service.WorldGuardService;
import net.democracycraft.vault.internal.region.VaultRegionImp;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * WorldGuard-backed service to resolve regions without maintaining an internal cache.
 *
 * Design:
 * - Every call adapts the Bukkit world to WorldEdit and pulls the current regions from WorldGuard's RegionManager.
 * - This avoids cache invalidation complexity because regions can change frequently at runtime.
 */
public class WorldGuardServiceImp implements WorldGuardService {

    private final RegionContainer regionContainer;

    public WorldGuardServiceImp() {
        this.regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
    }

    /**
     * Returns regions in the given world that overlap the provided bounding box.
     * Data is fetched live from WorldGuard on each call.
     *
     * @param boundingBox the query area (not null)
     * @param world       the Bukkit world (not null)
     * @return a list of regions overlapping the box (never null)
     */
    @Override
    public @NotNull List<VaultRegion> getRegionsAt(@NotNull BoundingBox boundingBox, @NotNull World world) {
        Objects.requireNonNull(boundingBox, "boundingBox");
        Objects.requireNonNull(world, "world");
        return getRegionsIn(world).stream()
                .filter(region -> region.boundingBox().overlaps(boundingBox))
                .toList();
    }

    /**
     * Returns all regions in the given world. This method queries WorldGuard's RegionManager
     * directly on each call and does not use an internal cache.
     *
     * @param world the Bukkit world (not null)
     * @return a list of regions in the world (never null)
     */
    @Override
    public @NotNull List<VaultRegion> getRegionsIn(@NotNull World world) {
        Objects.requireNonNull(world, "world");

        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        RegionManager manager = regionContainer.get(weWorld);
        if (manager == null) {
            return List.of();
        }

        List<VaultRegion> regions = new ArrayList<>(manager.getRegions().size());
        for (Map.Entry<String, ProtectedRegion> entry : manager.getRegions().entrySet()) {
            String id = entry.getKey();
            ProtectedRegion protectedRegion = entry.getValue();
            List<UUID> owners = new ArrayList<>(protectedRegion.getOwners().getPlayerDomain().getUniqueIds());
            List<UUID> members = new ArrayList<>(protectedRegion.getMembers().getPlayerDomain().getUniqueIds());
            BoundingBox box = getBoundingBox(protectedRegion);
            regions.add(new VaultRegionImp(id, members, owners, box));
        }
        return regions;
    }

    private @NotNull BoundingBox getBoundingBox(@NotNull ProtectedRegion region) {
        Objects.requireNonNull(region, "region");
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();

        return new BoundingBox(
                min.x(), min.y(), min.z(),
                max.x(), max.y(), max.z()
        );
    }
}
