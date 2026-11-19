package com.minecraftcitiesnetwork.vaultstorage.scan;

import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.scan.ScanResult;
import com.minecraftcitiesnetwork.vaultstorage.scan.VaultScanService;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultScanMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultUIContext;
import com.minecraftcitiesnetwork.vaultstorage.scan.VaultRegionScanner;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class VaultScanServiceImpl implements VaultScanService {

    private final @NotNull VaultStoragePlugin plugin;

    public VaultScanServiceImpl(@NotNull VaultStoragePlugin plugin) {
        this.plugin = plugin;
    }

    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<CacheKey, List<Consumer<List<ScanResult>>>> pendingScans = new HashMap<>();

    @Override
    public void scan(@NotNull Player player, @NotNull String regionId, @NotNull VaultUIContext context, @NotNull Consumer<List<ScanResult>> callback) {
        CacheKey key = new CacheKey(regionId, player.getUniqueId(), context.filterOwner());

        // Check cache
        if (cache.containsKey(key)) {
            CacheEntry entry = cache.get(key);
            long ttlSeconds = plugin.settings().get().scan().cacheTtlSeconds();
            if (Instant.now().isBefore(entry.timestamp.plusSeconds(ttlSeconds))) {
                // Cache hit and valid
                callback.accept(List.copyOf(entry.results));
                return;
            } else {
                // Cache expired
                cache.remove(key);
            }
        }

        // Check if a scan is already in progress for this key
        synchronized (pendingScans) {
            if (pendingScans.containsKey(key)) {
                pendingScans.get(key).add(callback);
                return;
            }
            List<Consumer<List<ScanResult>>> callbacks = new ArrayList<>();
            callbacks.add(callback);
            pendingScans.put(key, callbacks);
        }

        // Cache miss or expired, perform scan
        try {
            new VaultRegionScanner(this.plugin, player, regionId, context, results -> {
                if (results != null) {
                    // A copy that can shrink: forget() removes blocks from it, from any thread.
                    cache.put(key, new CacheEntry(new CopyOnWriteArrayList<>(results), Instant.now()));
                }
                resolveOwnerNames(results).whenComplete((done, failure) -> Bukkit.getScheduler().runTask(
                        plugin, () -> answer(key, results)));
            }).start();
        } catch (RuntimeException ex) {
            // Everyone waiting is answered and the scan stops counting as in progress; otherwise
            // every later scan of this region would wait behind one that never ran.
            plugin.getLogger().log(Level.SEVERE, "The scan of " + regionId + " could not start.", ex);
            answer(key, null);
        }
    }

    /** Answers everyone waiting on this scan, and stops recording it as in progress. */
    private void answer(@NotNull CacheKey key, @Nullable List<ScanResult> results) {
        List<Consumer<List<ScanResult>>> waiting;
        synchronized (pendingScans) {
            waiting = pendingScans.remove(key);
        }
        if (waiting != null) {
            for (Consumer<List<ScanResult>> callback : waiting) {
                callback.accept(results);
            }
        }
    }

    /** Looks up the name of every owner in the results, so the list can be shown once, complete. */
    private @NotNull CompletableFuture<Void> resolveOwnerNames(@Nullable List<ScanResult> results) {
        if (results == null) {
            return CompletableFuture.completedFuture(null);
        }
        Set<UUID> owners = new HashSet<>();
        for (ScanResult result : results) {
            if (result.owner() != null) {
                owners.add(result.owner());
            }
        }
        List<CompletableFuture<Void>> lookups = new ArrayList<>();
        for (UUID owner : owners) {
            lookups.add(plugin.names().nameOf(owner).thenAccept(name ->
                    name.filter(found -> !found.isBlank()).ifPresent(found -> VaultScanMenu.rememberName(owner, found))));
        }
        return CompletableFuture.allOf(lookups.toArray(new CompletableFuture[0]));
    }

    @Override
    public void forget(@NotNull Block block) {
        for (CacheEntry entry : cache.values()) {
            entry.results.removeIf(result -> sameBlock(result.block(), block));
        }
    }

    private static boolean sameBlock(@NotNull Block first, @NotNull Block second) {
        return first.getWorld().getUID().equals(second.getWorld().getUID())
                && first.getX() == second.getX() && first.getY() == second.getY() && first.getZ() == second.getZ();
    }

    private record CacheKey(@NotNull String regionId, @NotNull UUID playerUuid, @NotNull UUID filterOwner) {
        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey cacheKey = (CacheKey) o;
            return Objects.equals(regionId, cacheKey.regionId) &&
                    Objects.equals(playerUuid, cacheKey.playerUuid) &&
                    Objects.equals(filterOwner, cacheKey.filterOwner);
        }

        @Override
        public int hashCode() {
            return Objects.hash(regionId, playerUuid, filterOwner);
        }
    }

    private record CacheEntry(@NotNull List<ScanResult> results, @NotNull Instant timestamp) {}
}

