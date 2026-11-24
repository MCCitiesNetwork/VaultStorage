package com.minecraftcitiesnetwork.vaultstorage.scan;

import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.CompletableFuture;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultUIContext;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class VaultScanServiceImpl implements VaultScanService {

    private final @NotNull VaultStoragePlugin plugin;

    public VaultScanServiceImpl(@NotNull VaultStoragePlugin plugin) {
        this.plugin = plugin;
    }

    // Main thread only: a scan starts at a dialog click, and its results, its answer and
    // forget() all arrive there.
    private final Map<CacheKey, CacheEntry> cache = new HashMap<>();
    private final Map<CacheKey, List<Consumer<List<ScanResult>>>> pendingScans = new HashMap<>();

    @Override
    public void scan(@NotNull Player player, @NotNull String regionId, @NotNull VaultUIContext context, @NotNull Consumer<List<ScanResult>> callback) {
        CacheKey key = new CacheKey(regionId, player.getUniqueId(), context.filterOwner());

        // Check cache
        if (cache.containsKey(key)) {
            CacheEntry entry = cache.get(key);
            long ttlSeconds = plugin.settings().scan().cacheTtlSeconds();
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
        List<Consumer<List<ScanResult>>> waiting = pendingScans.get(key);
        if (waiting != null) {
            waiting.add(callback);
            return;
        }
        pendingScans.put(key, new ArrayList<>(List.of(callback)));

        // Cache miss or expired, perform scan
        try {
            new VaultRegionScanner(this.plugin, player, regionId, context, results -> {
                if (results != null) {
                    // Results nobody asked for again are dropped here, so the cache does not
                    // grow with every region and player that ever scanned.
                    Instant stale = Instant.now().minusSeconds(plugin.settings().scan().cacheTtlSeconds());
                    cache.values().removeIf(entry -> entry.timestamp.isBefore(stale));
                    // A copy that can shrink: forget() removes blocks from it.
                    cache.put(key, new CacheEntry(new ArrayList<>(results), Instant.now()));
                }
                resolveOwnerNames(results).whenCompleteAsync((done, failure) -> answer(key, results),
                        plugin.mainThread());
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
        List<Consumer<List<ScanResult>>> waiting = pendingScans.remove(key);
        if (waiting != null) {
            for (Consumer<List<ScanResult>> callback : waiting) {
                callback.accept(results);
            }
        }
    }

    /** Looks up the name of every owner in the results, so the list can be shown once, complete. */
    private @NotNull CompletableFuture<?> resolveOwnerNames(@Nullable List<ScanResult> results) {
        if (results == null) {
            return CompletableFuture.completedFuture(null);
        }
        return plugin.names().displayNames(results.stream().map(ScanResult::owner).filter(Objects::nonNull).toList());
    }

    @Override
    public void forget(@NotNull Block block) {
        for (CacheEntry entry : cache.values()) {
            entry.results.removeIf(result -> result.block().equals(block));
        }
    }

    private record CacheKey(@NotNull String regionId, @NotNull UUID playerUuid, @Nullable UUID filterOwner) {
    }

    private record CacheEntry(@NotNull List<ScanResult> results, @NotNull Instant timestamp) {}
}

