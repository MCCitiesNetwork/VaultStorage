package com.minecraftcitiesnetwork.vaultstorage.region;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import com.minecraftcitiesnetwork.vaultstorage.region.WorldGuardService;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegionImp;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
     * The regions that contain the block, by their real shape. WorldGuard answers from its
     * spatial index, so this neither builds every region in the world nor treats a polygon as
     * its bounding box.
     */
    @Override
    public @NotNull List<VaultRegion> getRegionsAt(@NotNull Block block) {
        Objects.requireNonNull(block, "block");
        RegionManager manager = regionContainer.get(BukkitAdapter.adapt(block.getWorld()));
        if (manager == null) {
            return List.of();
        }
        List<VaultRegion> regions = new ArrayList<>();
        for (ProtectedRegion region : manager.getApplicableRegions(
                BlockVector3.at(block.getX(), block.getY(), block.getZ()))) {
            regions.add(buildVaultRegion(region.getId(), region));
        }
        return regions;
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

            regions.add(buildVaultRegion(entry.getValue().getId(), entry.getValue()));
        }
        return regions;
    }

    @Override
    @Nullable
    public VaultRegion getRegionById(@NotNull String id, @NotNull World world) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(world, "world");

        List<VaultRegion> regions = getRegionsIn(world);

        return regions.stream().filter(region -> region.id().equals(id)).findFirst().orElse(null);
    }

    private @NotNull VaultRegion buildVaultRegion(@NotNull String id, @NotNull ProtectedRegion protectedRegion) {
        var ownerSet = new LinkedHashSet<UUID>();
        var memberSet = new LinkedHashSet<UUID>();
        ProtectedRegion cursor = protectedRegion;
        while (cursor != null) {
            ownerSet.addAll(cursor.getOwners().getPlayerDomain().getUniqueIds());
            memberSet.addAll(cursor.getMembers().getPlayerDomain().getUniqueIds());
            cursor = cursor.getParent();
        }
        int priority = protectedRegion.getPriority();
        BoundingBox box = getBoundingBox(protectedRegion);
        return new VaultRegionImp(id, new ArrayList<>(memberSet), new ArrayList<>(ownerSet), box, priority);
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
