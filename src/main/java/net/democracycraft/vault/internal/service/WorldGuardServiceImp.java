package net.democracycraft.vault.internal.service;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.region.VaultRegion;
import net.democracycraft.vault.api.service.WorldGuardService;
import net.democracycraft.vault.internal.region.VaultRegionImp;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WorldGuard-backed service to resolve regions and cache them per world.
 *
 * Caching strategy:
 * - Per-world NavigableMap keyed by WorldGuard region id for stable updates by id.
 * - First query for a world loads all regions; subsequent calls reuse and selectively refresh.
 * - No persistence here; WorldGuard persists regions. Invalidation APIs provided for runtime changes.
 */
public class WorldGuardServiceImp implements WorldGuardService {

    private final VaultStoragePlugin plugin;

    private final WorldGuard worldGuardApi;

    private final RegionContainer regionContainer;

    // Per-world cache: world UUID -> sorted map (by region id) of VaultRegion
    private final Map<UUID, NavigableMap<String, VaultRegion>> regionCache = new ConcurrentHashMap<>();

    public WorldGuardServiceImp(@NotNull VaultStoragePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.worldGuardApi = WorldGuard.getInstance();
        this.regionContainer = worldGuardApi.getPlatform().getRegionContainer();
    }

    /**
     * Returns all cached regions in the given world that overlap the provided bounding box.
     * If the world is not cached yet, it will be loaded and cached first.
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
     * Returns all regions in the given world, using a per-world cache populated on first access.
     *
     * @param world the Bukkit world (not null)
     * @return a list view of all cached regions in the world (never null)
     */
    @Override
    public @NotNull List<VaultRegion> getRegionsIn(@NotNull World world) {
        Objects.requireNonNull(world, "world");
        UUID worldId = world.getUID();

        NavigableMap<String, VaultRegion> map = regionCache.get(worldId);
        if (map == null) {
            map = new TreeMap<>();
            // Load and cache regions for this world
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            RegionManager manager = regionContainer.get(weWorld);
            if (manager == null) {
                regionCache.put(worldId, map);
                return List.of();
            }

            for (Map.Entry<String, ProtectedRegion> entry : manager.getRegions().entrySet()) {
                String id = entry.getKey();
                ProtectedRegion protectedRegion = entry.getValue();
                List<UUID> owners = new ArrayList<>(protectedRegion.getOwners().getPlayerDomain().getUniqueIds());
                List<UUID> members = new ArrayList<>(protectedRegion.getMembers().getPlayerDomain().getUniqueIds());
                BoundingBox box = getBoundingBox(protectedRegion);
                VaultRegion vaultRegion = new VaultRegionImp(id, members, owners, box);
                map.put(id, vaultRegion);
            }
            regionCache.put(worldId, map);
        }

        return new ArrayList<>(map.values());
    }

    @Override
    public void invalidateCache(@NotNull World world) {
        Objects.requireNonNull(world, "world");
        regionCache.remove(world.getUID());
    }

    @Override
    public void refreshWorld(@NotNull World world) {
        Objects.requireNonNull(world, "world");
        regionCache.remove(world.getUID());
        getRegionsIn(world); // repopulate lazily
    }

    @Override
    public void refreshRegion(@NotNull World world, @NotNull String regionId) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(regionId, "regionId");
        UUID worldId = world.getUID();
        NavigableMap<String, VaultRegion> map = regionCache.computeIfAbsent(worldId, k -> new TreeMap<>());

        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        RegionManager manager = regionContainer.get(weWorld);
        if (manager == null) {
            map.remove(regionId);
            return;
        }
        ProtectedRegion pr = manager.getRegion(regionId);
        if (pr == null) {
            map.remove(regionId);
            return;
        }
        List<UUID> owners = new ArrayList<>(pr.getOwners().getPlayerDomain().getUniqueIds());
        List<UUID> members = new ArrayList<>(pr.getMembers().getPlayerDomain().getUniqueIds());
        BoundingBox box = getBoundingBox(pr);
        map.put(regionId, new VaultRegionImp(regionId, members, owners, box));
    }

    @Override
    public void removeRegion(@NotNull World world, @NotNull String regionId) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(regionId, "regionId");
        NavigableMap<String, VaultRegion> map = regionCache.get(world.getUID());
        if (map != null) {
            map.remove(regionId);
        }
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
