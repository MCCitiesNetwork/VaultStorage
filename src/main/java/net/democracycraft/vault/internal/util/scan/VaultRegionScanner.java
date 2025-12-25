package net.democracycraft.vault.internal.util.scan;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.api.service.WorldGuardService;
import net.democracycraft.vault.internal.security.VaultCapturePolicy;
import net.democracycraft.vault.api.data.ScanResult;
import net.democracycraft.vault.internal.ui.VaultScanMenu;
import net.democracycraft.vault.internal.ui.VaultUIContext;
import net.democracycraft.vault.internal.util.config.ConfigPaths;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.Protection;

import java.util.*;
import java.util.function.Consumer;

public class VaultRegionScanner {

    private final Player player;
    private final String regionId;
    private final VaultUIContext uiContext;
    private final VaultScanMenu.Config config;
    private final Consumer<List<ScanResult>> callback;
    private BukkitTask task;

    public VaultRegionScanner(Player player, String regionId, VaultUIContext uiContext, VaultScanMenu.Config config, Consumer<List<ScanResult>> callback) {
        this.player = player;
        this.regionId = regionId;
        this.uiContext = uiContext;
        this.config = config;
        this.callback = callback;
    }

    public void start() {
        World world = player.getWorld();
        WorldGuardService worldGuardService = VaultStoragePlugin.getInstance().getWorldGuardService();
        BoltService boltService = VaultStoragePlugin.getInstance().getBoltService();

        if (worldGuardService == null || boltService == null) {
            player.sendMessage(MiniMessageUtil.parseOrPlain(config.servicesMissing));
            callback.accept(null);
            return;
        }


        var regions = worldGuardService.getRegionsIn(world);
        var target = regions.stream().filter(r -> r.id().equalsIgnoreCase(regionId)).findFirst();
        if (target.isEmpty()) {
            player.sendMessage(MiniMessageUtil.parseOrPlain(config.regionNotFound, Map.of("%region%", regionId)));
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

        this.task = new ScanTask(blockProtections, world, actorOwnsRegion).runTaskTimer(VaultStoragePlugin.getInstance(), 1L, 1L);
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

        public ScanTask(List<BlockProtection> protections, World world, boolean actorOwnsRegion) {
            this.protections = protections;
            this.world = world;
            this.actorOwnsRegion = actorOwnsRegion;
            this.batchSize = VaultStoragePlugin.getInstance().getConfig().getInt(ConfigPaths.SCAN_BATCH_SIZE.getPath(), 50);
        }

        @Override
        public void run() {
            if (!player.isOnline()) {
                this.cancel();
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

                // Load chunk if needed
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    world.getChunkAt(x >> 4, z >> 4); // This loads it
                }

                Block block = world.getBlockAt(x, y, z);

                // Policy check
                VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluate(player, block);
                if (!decision.allowed()) continue;

                // Owner filter
                if (!actorOwnsRegion && uiContext.filterOwner() != null) {
                    UUID owner = bp.getOwner(); // Use protection owner directly to avoid another lookup
                    if (owner == null || !owner.equals(uiContext.filterOwner())) continue;
                }

                results.add(new ScanResult(block, bp.getOwner(), block.getType()));
            }

            if (index >= protections.size()) {
                this.cancel();
                callback.accept(results);
            }
        }
    }
}
