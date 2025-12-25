package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.ScanResult;
import net.democracycraft.vault.api.service.VaultScanService;
import net.democracycraft.vault.internal.ui.VaultScanMenu;
import net.democracycraft.vault.internal.ui.VaultUIContext;
import net.democracycraft.vault.internal.util.config.ConfigPaths;
import net.democracycraft.vault.internal.util.scan.VaultRegionScanner;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class VaultScanServiceImpl implements VaultScanService {

    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<CacheKey, List<Consumer<List<ScanResult>>>> pendingScans = new HashMap<>();

    @Override
    public void scan(Player player, String regionId, VaultUIContext context, VaultScanMenu.Config config, Consumer<List<ScanResult>> callback) {
        CacheKey key = new CacheKey(regionId, player.getUniqueId(), context.filterOwner());

        // Check cache
        if (cache.containsKey(key)) {
            CacheEntry entry = cache.get(key);
            long ttlSeconds = VaultStoragePlugin.getInstance().getConfig().getLong(ConfigPaths.SCAN_CACHE_TTL_SECONDS.getPath(), 60);
            if (Instant.now().isBefore(entry.timestamp.plusSeconds(ttlSeconds))) {
                // Cache hit and valid
                callback.accept(entry.results);
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
        new VaultRegionScanner(player, regionId, context, config, results -> {
            if (results != null) {
                cache.put(key, new CacheEntry(results, Instant.now()));
            }

            List<Consumer<List<ScanResult>>> waiting;
            synchronized (pendingScans) {
                waiting = pendingScans.remove(key);
            }

            if (waiting != null) {
                for (Consumer<List<ScanResult>> cb : waiting) {
                    cb.accept(results);
                }
            }
        }).start();
    }

    @Override
    public void invalidateCache() {
        cache.clear();
    }

    private record CacheKey(String regionId, UUID playerUuid, UUID filterOwner) {
        @Override
        public boolean equals(Object o) {
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

    private record CacheEntry(List<ScanResult> results, Instant timestamp) {}
}

