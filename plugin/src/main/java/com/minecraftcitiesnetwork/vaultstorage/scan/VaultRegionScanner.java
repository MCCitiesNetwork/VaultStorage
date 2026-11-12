package com.minecraftcitiesnetwork.vaultstorage.scan;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Chunk;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.region.WorldGuardService;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCapturePolicy;
import com.minecraftcitiesnetwork.vaultstorage.scan.ScanResult;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultUIContext;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.Protection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class VaultRegionScanner {

    private final @NotNull VaultStoragePlugin plugin;

    private final Player player;
    private final String regionId;
    private final VaultUIContext uiContext;
    private final Consumer<List<ScanResult>> callback;
    private BukkitTask task;
    /** Chunks held loaded for this scan. Main thread only. */
    private final List<Chunk> heldChunks = new ArrayList<>();

    public VaultRegionScanner(@NotNull VaultStoragePlugin plugin, @NotNull Player player, @NotNull String regionId, @NotNull VaultUIContext uiContext, @NotNull Consumer<List<ScanResult>> callback) {
        this.plugin = plugin;
        this.player = player;
        this.regionId = regionId;
        this.uiContext = uiContext;
        this.callback = callback;
    }

    public void start() {
        World world = player.getWorld();
        WorldGuardService worldGuardService = plugin.worldGuard();
        BoltService boltService = plugin.bolt();

        if (worldGuardService == null || boltService == null) {
            // The caller put up a loading dialog; nothing will replace it.
            player.closeDialog();
            player.sendMessage(plugin.text().of(MessageKeys.SCAN_SERVICES_MISSING));
            callback.accept(null);
            return;
        }


        var regions = worldGuardService.getRegionsIn(world);
        var target = regions.stream().filter(r -> r.id().equalsIgnoreCase(regionId)).findFirst();
        if (target.isEmpty()) {
            player.closeDialog();
            player.sendMessage(plugin.text().of(MessageKeys.SCAN_REGION_NOT_FOUND, Map.of("%region%", regionId)));
            callback.accept(null);
            return;
        }

        var region = target.get();
        boolean actorOwnsRegion = region.isOwner(player.getUniqueId());
        BoundingBox boundingBox = region.boundingBox();

        Collection<Protection> protections = boltService.getProtections(boundingBox, world);
        if (protections == null || protections.isEmpty()) {
            callback.accept(new ArrayList<>());
            return;
        }

        List<BlockProtection> blockProtections = new ArrayList<>();
        for (Protection protection : protections) {
            if (protection instanceof BlockProtection bp) {
                blockProtections.add(bp);
            }
        }

        // Every chunk the scan reads is loaded off the main thread first and held until the scan
        // ends, so reading a block never loads a chunk on the main thread.
        Set<Long> chunkKeys = new LinkedHashSet<>();
        for (BlockProtection protection : blockProtections) {
            chunkKeys.add(Chunk.getChunkKey(protection.getX() >> 4, protection.getZ() >> 4));
        }
        List<CompletableFuture<Void>> loads = new ArrayList<>();
        for (long key : chunkKeys) {
            int chunkX = (int) key;
            int chunkZ = (int) (key >> 32);
            loads.add(world.getChunkAtAsync(chunkX, chunkZ).thenAccept(chunk -> {
                chunk.addPluginChunkTicket(plugin);
                this.heldChunks.add(chunk);
            }));
        }
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((done, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING, "A chunk could not be loaded for a scan of " + regionId + ".", failure);
            }
            this.task = new ScanTask(blockProtections, world, actorOwnsRegion).runTaskTimer(plugin, 1L, 1L);
        });
    }

    public void cancel() {
        if (this.task != null && !this.task.isCancelled()) {
            this.task.cancel();
        }
    }

    private class ScanTask extends BukkitRunnable {
        private final List<BlockProtection> protections;
        private final World world;
        private final boolean actorOwnsRegion;
        private final List<ScanResult> results = new ArrayList<>();
        private int index = 0;
        private final int batchSize;

        public ScanTask(@NotNull List<BlockProtection> protections, @NotNull World world, boolean actorOwnsRegion) {
            this.protections = protections;
            this.world = world;
            this.actorOwnsRegion = actorOwnsRegion;
            this.batchSize = plugin.settings().get().scan().batchSize();
        }

        @Override
        public void run() {
            if (!player.isOnline()) {
                // Still answer: the scan service is waiting to clear its record of this scan.
                finish(null);
                return;
            }

            long startTime = System.currentTimeMillis();
            int processed = 0;

            while (index < protections.size() && processed < batchSize) {
                if (System.currentTimeMillis() - startTime > 2) {
                    break;
                }

                BlockProtection bp = protections.get(index);
                index++;
                processed++;

                int x = bp.getX();
                int y = bp.getY();
                int z = bp.getZ();

                if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;

                // Loaded and held by start(). One that failed to load is skipped, not loaded here.
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }

                Block block = world.getBlockAt(x, y, z);

                // Policy check
                VaultCapturePolicy.Decision decision = plugin.capturePolicy().evaluate(player, block);
                if (!decision.allowed()) continue;

                // Owner filter
                if (!actorOwnsRegion && uiContext.filterOwner() != null) {
                    UUID owner = bp.getOwner(); // Use protection owner directly to avoid another lookup
                    if (owner == null || !owner.equals(uiContext.filterOwner())) continue;
                }

                results.add(new ScanResult(block, bp.getOwner(), block.getType()));
            }

            if (index >= protections.size()) {
                finish(results);
            }
        }

        /** Ends the scan: stops the task, lets the chunks go, and answers exactly once. */
        private void finish(@Nullable List<ScanResult> answer) {
            this.cancel();
            for (Chunk chunk : heldChunks) {
                chunk.removePluginChunkTicket(plugin);
            }
            heldChunks.clear();
            callback.accept(answer);
        }
    }
}
