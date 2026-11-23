package com.minecraftcitiesnetwork.vaultstorage.region;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * WorldGuard-backed service to resolve regions without maintaining an internal cache.
 *
 * Design:
 * - Every call adapts the Bukkit world to WorldEdit and pulls the current regions from WorldGuard's RegionManager.
 * - This avoids cache invalidation complexity because regions can change frequently at runtime.
 */
public final class WorldGuardServiceImp implements WorldGuardService {

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

    @Override
    public @NotNull List<String> regionIds(@NotNull World world, @NotNull String nameContains, @Nullable UUID partOf) {
        RegionManager manager = regionContainer.get(BukkitAdapter.adapt(world));
        if (manager == null) {
            return List.of();
        }
        String wanted = nameContains.toLowerCase(Locale.ROOT);
        return manager.getRegions().values().stream()
                .filter(region -> region.getId().toLowerCase(Locale.ROOT).contains(wanted))
                .filter(region -> partOf == null || hasPart(region, partOf))
                .map(ProtectedRegion::getId)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** As {@link VaultRegion#isPartOfRegion}: an owner or member of the region or of a parent. */
    private static boolean hasPart(@NotNull ProtectedRegion region, @NotNull UUID player) {
        for (ProtectedRegion cursor = region; cursor != null; cursor = cursor.getParent()) {
            if (cursor.getOwners().getPlayerDomain().contains(player)
                    || cursor.getMembers().getPlayerDomain().contains(player)) {
                return true;
            }
        }
        return false;
    }

    /** Asks the one region, so nothing is built. */
    @Override
    public boolean isInside(@NotNull String regionId, @NotNull Block block) {
        RegionManager manager = regionContainer.get(BukkitAdapter.adapt(block.getWorld()));
        ProtectedRegion region = manager == null ? null : manager.getRegion(regionId);
        return region != null && region.contains(block.getX(), block.getY(), block.getZ());
    }

    @Override
    @Nullable
    public VaultRegion getRegionById(@NotNull String id, @NotNull World world) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(world, "world");

        RegionManager manager = regionContainer.get(BukkitAdapter.adapt(world));
        ProtectedRegion region = manager == null ? null : manager.getRegion(id);
        return region == null ? null : buildVaultRegion(region.getId(), region);
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
        BoundingBox box = getBoundingBox(protectedRegion);
        return new VaultRegionImp(id, new ArrayList<>(memberSet), new ArrayList<>(ownerSet), box);
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
