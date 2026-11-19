package com.minecraftcitiesnetwork.vaultstorage.autovault;

import org.bukkit.block.Block;
import java.util.concurrent.Executor;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import com.minecraftcitiesnetwork.vaultstorage.capture.ContainerBlocks;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.ChestShopService;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.ShopPosition;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.ShopOwners;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.ShopOwner;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.ShopDirectory;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.Shop;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCaptureService;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.region.WorldGuardService;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.autovault.RegionKey;
import com.minecraftcitiesnetwork.vaultstorage.autovault.SweepFilter;
import com.minecraftcitiesnetwork.vaultstorage.autovault.OfflineRegionScanner;
import com.minecraftcitiesnetwork.vaultstorage.autovault.OfflineRegionScanner.DisplacedContainer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Schedules and runs the delayed, batched auto-vaulting of Bolt-locked containers (and, when enabled,
 * item frames and paintings) when a region's owner/tenant changes. A lock is vaulted when its Bolt owner
 * is not in the allowed set ({new occupant} plus the region's WorldGuard owners/members). Runs on the
 * main thread; chunk loads are async.
 */
public final class AutoVaultService {

    private final VaultStoragePlugin plugin;
    // Main-thread only: populated from the Realty event handler and the scheduled sweep task.
    private final Map<RegionKey, BukkitTask> pending = new HashMap<>();
    private final PendingSweepStore store;
    // Read at use time: a reload swaps what it holds.
    private final AtomicReference<AutoVaultSettings> settings;

    public AutoVaultService(@NotNull VaultStoragePlugin plugin, @NotNull PendingSweepStore store,
                            @NotNull AtomicReference<AutoVaultSettings> settings) {
        this.plugin = plugin;
        this.store = store;
        this.settings = settings;
    }

    /**
     * Schedules every sweep that was still waiting when the server last stopped. One that came
     * due meanwhile runs shortly after startup.
     */
    public void resume() {
        CompletableFuture.supplyAsync(this.store::all, this.plugin.databaseExecutor())
                .thenAcceptAsync(sweeps -> {
                    Instant now = Instant.now();
                    for (PendingSweep sweep : sweeps) {
                        schedule(sweep, sweep.ticksUntilDue(now));
                    }
                    if (!sweeps.isEmpty()) {
                        this.plugin.getLogger().info("Resumed " + sweeps.size() + " auto-vault sweep(s) from before the restart.");
                    }
                }, this.plugin.getServer().getScheduler().getMainThreadExecutor(this.plugin))
                .exceptionally(failure -> {
                    this.plugin.getLogger().log(Level.SEVERE, "Could not read the pending auto-vault sweeps.", failure);
                    return null;
                });
    }

    /**
     * Schedules an auto-vault sweep of {@code regionId} after the configured grace delay.
     * Cancels any pending sweep already queued for the same region+world.
     *
     * @param worldId   world the region lives in
     * @param regionId  WorldGuard region id
     * @param initiator the new occupant (owner/tenant); recorded as vault creator and exempt from vaulting
     */
    public void submit(@NotNull UUID worldId, @NotNull String regionId, @NotNull UUID initiator) {
        AutoVaultSettings current = this.settings.get();
        if (!current.enabled()) {
            return;
        }
        long delayTicks = current.delayTicks();
        PendingSweep sweep = new PendingSweep(worldId, regionId, initiator, Instant.now().plusMillis(delayTicks * 50L));
        // Stored so that a restart during the grace period does not lose the sweep.
        CompletableFuture.runAsync(() -> this.store.save(sweep), this.plugin.databaseExecutor())
                .exceptionally(failure -> {
                    this.plugin.getLogger().log(Level.SEVERE, "Could not store the pending sweep of " + regionId
                            + "; it will be lost if the server restarts before it runs.", failure);
                    return null;
                });
        schedule(sweep, delayTicks);
    }

    /** Main thread. Replaces a sweep already waiting for the same region. */
    private void schedule(@NotNull PendingSweep sweep, long delayTicks) {
        RegionKey key = new RegionKey(sweep.worldUuid(), sweep.regionId());
        UUID initiator = sweep.initiatorUuid();
        BukkitTask task = new BukkitRunnable() {
            @Override public void run() {
                // A fired task is always the current pending entry (a resubmit cancels the prior one).
                pending.remove(key);
                runSweep(key, initiator);
            }
        }.runTaskLater(plugin, delayTicks);
        BukkitTask previous = pending.put(key, task);
        if (previous != null) {
            previous.cancel();
        }
    }

    /**
     * Returns the callback the capture service runs after each vault it creates during a sweep. Only the first
     * invocation notifies the occupant (if online and enabled); the rest are no-ops. Thread-safe: may be called
     * from async capture tasks, and hops the message send onto the main thread.
     */
    private @NotNull Runnable vaultedNotifier(@NotNull UUID initiator) {
        if (!this.settings.get().notifyOccupant()) {
            return () -> {};
        }
        AtomicBoolean sent = new AtomicBoolean(false);
        return () -> {
            if (!sent.compareAndSet(false, true)) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player occupant = Bukkit.getPlayer(initiator);
                if (occupant == null) {
                    return;
                }
                occupant.sendMessage(plugin.messages().messageFor(MessageKeys.AUTO_VAULT_OCCUPANT_NOTIFIED));
            });
        };
    }

    private void runSweep(@NotNull RegionKey key, @NotNull UUID initiator) {
        if (!this.settings.get().enabled()) {
            // Turned off while it waited. The stored sweep stays for a restart with it back on.
            return;
        }
        World world = Bukkit.getWorld(key.worldId());
        if (world == null) {
            // The world is not loaded. The stored sweep stays and is tried again after a restart.
            return;
        }
        WorldGuardService wgs = plugin.worldGuard();
        // Resolve every region once: the swept region plus the sweep filter both come from this single list.
        List<VaultRegion> regions = wgs.getRegionsIn(world);
        VaultRegion region = regions.stream().filter(r -> r.id().equals(key.regionId())).findFirst().orElse(null);
        if (region == null) {
            // Region was deleted during the delay window; nothing to sweep.
            forget(key);
            return;
        }

        SweepFilter filter = SweepFilter.forSweep(regions, region, initiator);
        // One notifier shared by both passes, so the occupant is messaged at most once per sweep.
        Runnable onVaulted = vaultedNotifier(initiator);
        Sweep sweep = new Sweep();

        List<DisplacedContainer> displaced = OfflineRegionScanner.scan(plugin.bolt(), key.worldId(), region.boundingBox(), filter);
        CompletableFuture<Void> blocksDispatched = displaced == null || displaced.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : vaultBlocks(key.worldId(), displaced, initiator, onVaulted, sweep);
        CompletableFuture<Void> hangingsDispatched =
                this.settings.get().includeHangings()
                        ? vaultHangings(key.worldId(), region.boundingBox(), filter, initiator, onVaulted, sweep)
                        : CompletableFuture.completedFuture(null);

        CompletableFuture<Void> shopsDispatched =
                vaultShops(world, region.boundingBox(), filter, initiator, onVaulted, sweep);

        // Every capture has been started once the passes have visited their chunks; the sweep is
        // over when the last of them has finished.
        CompletableFuture.allOf(blocksDispatched, hangingsDispatched, shopsDispatched)
                .thenCompose(dispatched -> sweep.allCaptures())
                .whenCompleteAsync((done, failure) -> {
                    if (failure != null) {
                        plugin.getLogger().log(Level.SEVERE, "The auto-vault sweep of " + key.regionId() + " failed.", failure);
                    }
                    // One message per previous owner, however many of their containers were vaulted.
                    sweep.vaultedPerOwner().forEach((owner, count) ->
                            plugin.ownerNotifications().sweepVaulted(owner, initiator, count, key.regionId()));
                    forget(key);
                }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin));
    }

    /** Removes the stored sweep: it has run, or has nothing left to do. */
    private void forget(@NotNull RegionKey key) {
        CompletableFuture.runAsync(() -> this.store.delete(key.worldId(), key.regionId()), this.plugin.databaseExecutor())
                .exceptionally(failure -> {
                    this.plugin.getLogger().log(Level.WARNING, "Could not remove the finished sweep of " + key.regionId()
                            + "; it will run again after a restart.", failure);
                    return null;
                });
    }

    /** What one sweep has started and what came of it. Captures finish on the main thread. */
    private static final class Sweep {
        private final List<CompletableFuture<Boolean>> captures = new ArrayList<>();
        private final Map<UUID, Integer> vaultedPerOwner = new HashMap<>();

        void track(@NotNull UUID owner, @NotNull CompletableFuture<Boolean> capture) {
            this.captures.add(capture.thenApply(vaulted -> {
                if (vaulted) {
                    this.vaultedPerOwner.merge(owner, 1, Integer::sum);
                }
                return vaulted;
            }));
        }

        @NotNull CompletableFuture<Void> allCaptures() {
            return CompletableFuture.allOf(this.captures.toArray(new CompletableFuture[0]));
        }

        @NotNull Map<UUID, Integer> vaultedPerOwner() {
            return this.vaultedPerOwner;
        }
    }

    /** Vaults each displaced container, loading its chunk asynchronously. */
    private @NotNull CompletableFuture<Void> vaultBlocks(@NotNull UUID worldId, @NotNull List<DisplacedContainer> displaced,
                                                        @NotNull UUID initiator, @NotNull Runnable onVaulted,
                                                        @NotNull Sweep sweep) {
        VaultCaptureService captureService = plugin.captureService();
        return forEachChunkPaced(worldId, displaced,
                dc -> new int[]{dc.x() >> 4, dc.z() >> 4},
                (world, chunk, dc) ->
                        // Tolerant of blocks changed since the scan (non-containers just drop their protection).
                        sweep.track(dc.owner(), captureService.captureOfflineAsync(
                                world.getBlockAt(dc.x(), dc.y(), dc.z()), initiator, dc.owner(), onVaulted)));
    }

    /**
     * Vaults displaced item frames and paintings across the region. Entity protections are not indexed
     * by coordinate, so every chunk in the region's bounds is loaded and its hanging entities inspected.
     */
    private @NotNull CompletableFuture<Void> vaultHangings(@NotNull UUID worldId, @NotNull BoundingBox box,
                                                          @NotNull SweepFilter filter, @NotNull UUID initiator,
                                                          @NotNull Runnable onVaulted, @NotNull Sweep sweep) {
        VaultCaptureService captureService = plugin.captureService();
        BoltService bolt = plugin.bolt();

        List<int[]> chunks = chunksOf(box);

        return forEachChunkPaced(worldId, chunks,
                coords -> coords,
                (world, chunk, coords) -> {
                    for (Entity entity : chunk.getEntities()) {
                        if (!(entity instanceof ItemFrame) && !(entity instanceof Painting)) continue;
                        UUID owner = bolt.getOwner(entity);
                        if (!filter.shouldVault(owner, entity.getX(), entity.getY(), entity.getZ())) continue;
                        sweep.track(owner, captureService.captureHangingOfflineAsync(
                                (Hanging) entity, owner, initiator, onVaulted));
                    }
                });
    }

    /** Every chunk a region's bounds touch, as {x, z}. */
    private static @NotNull List<int[]> chunksOf(@NotNull BoundingBox box) {
        List<int[]> chunks = new ArrayList<>();
        int minChunkX = (int) Math.floor(box.getMinX()) >> 4;
        int minChunkZ = (int) Math.floor(box.getMinZ()) >> 4;
        int maxChunkX = (int) Math.floor(box.getMaxX()) >> 4;
        int maxChunkZ = (int) Math.floor(box.getMaxZ()) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                chunks.add(new int[]{cx, cz});
            }
        }
        return chunks;
    }

    /**
     * Deals with the shops of displaced players whose containers nobody locked: the pass over
     * Bolt protections cannot see those. A stocked container is vaulted to the shop's player; an
     * empty one stays and only its shop signs go. Does nothing without a shop registry.
     *
     * <p>A container with a Bolt owner is left to the Bolt pass, which has already decided about
     * it. A firm's, an admin shop's and an unlisted shop's container has no player to own a
     * vault and is left where it is.
     */
    private @NotNull CompletableFuture<Void> vaultShops(@NotNull World world, @NotNull BoundingBox box,
                                                       @NotNull SweepFilter filter, @NotNull UUID initiator,
                                                       @NotNull Runnable onVaulted, @NotNull Sweep sweep) {
        ShopDirectory directory = plugin.integrations().shopDirectory();
        if (directory == null || !plugin.chestShop().isAvailable()) {
            return CompletableFuture.completedFuture(null);
        }
        UUID worldId = world.getUID();
        List<CompletableFuture<List<Shop>>> reads = new ArrayList<>();
        for (int[] chunk : chunksOf(box)) {
            reads.add(directory.shopsInChunk(world.getName(), chunk[0], chunk[1]));
        }
        Executor mainThread = plugin.getServer().getScheduler().getMainThreadExecutor(plugin);
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new))
                .thenComposeAsync(read -> {
                    List<Shop> registry = new ArrayList<>();
                    reads.forEach(result -> registry.addAll(result.join()));
                    // Only a player's shop inside the swept region whose player is displaced.
                    List<Shop> candidates = registry.stream()
                            .filter(shop -> shop.owner() instanceof ShopOwner.Player player
                                    && filter.shouldVault(player.uuid(), shop.sign().x(), shop.sign().y(), shop.sign().z()))
                            .toList();
                    // Two signs on one container must not start two captures of it.
                    Set<ShopPosition> handled = new HashSet<>();
                    return forEachChunkPaced(worldId, candidates,
                            shop -> new int[]{shop.sign().chunkX(), shop.sign().chunkZ()},
                            (loaded, chunk, shop) -> sweepShop(loaded, shop, registry, handled, filter, initiator,
                                    onVaulted, sweep));
                }, mainThread);
    }

    /** Main thread, with the sign's chunk loaded. */
    private void sweepShop(@NotNull World world, @NotNull Shop shop, @NotNull List<Shop> registry,
                           @NotNull Set<ShopPosition> handled, @NotNull SweepFilter filter, @NotNull UUID initiator,
                           @NotNull Runnable onVaulted, @NotNull Sweep sweep) {
        ChestShopService chestShop = plugin.chestShop();
        Block sign = world.getBlockAt(shop.sign().x(), shop.sign().y(), shop.sign().z());
        if (!chestShop.isShopSign(sign)) {
            // The registry is behind the world: the sign has gone since.
            return;
        }
        Block container = ContainerBlocks.attachedContainer(sign);
        if (container == null || !handled.add(new ShopPosition(container.getX(), container.getY(), container.getZ()))) {
            return;
        }
        BoltService bolt = plugin.bolt();
        if (bolt != null && bolt.getOwner(container) != null) {
            return;
        }
        List<Block> signs = chestShop.findShopSigns(container);
        Optional<UUID> owner = ShopOwners.playerOwner(registry, signs.stream()
                .map(block -> new ShopPosition(block.getX(), block.getY(), block.getZ())).toList());
        if (owner.isEmpty() || !filter.shouldVault(owner.get(), container.getX(), container.getY(), container.getZ())) {
            return;
        }
        if (ContainerBlocks.isEmpty(container)) {
            // Nothing to store. The shop is still the displaced player's, so its signs go.
            signs.forEach(block -> chestShop.removeShopSign(null, block));
            return;
        }
        // The shop plugin sees the container go and closes the shop itself.
        sweep.track(owner.get(), plugin.captureService().captureOfflineAsync(container, initiator, owner.get(), onVaulted));
    }

    /**
     * Runs {@code work} for each item, loading the chunk given by {@code chunkOf} asynchronously and keeping
     * at most {@code scan.batch-size} loads in flight. {@code work} runs on the main thread once that chunk is
     * loaded. Stops if the world unloads.
     */
    private <T> @NotNull CompletableFuture<Void> forEachChunkPaced(@NotNull UUID worldId, @NotNull List<T> items,
                                                                  @NotNull Function<T, int[]> chunkOf,
                                                                  @NotNull ChunkWork<T> work) {
        final int maxInFlight = Math.max(1, plugin.settings().get().scan().batchSize());
        CompletableFuture<Void> dispatched = new CompletableFuture<>();

        new BukkitRunnable() {
            int index = 0;
            int inFlight = 0; // pending async chunk loads; mutated only on the main thread

            @Override public void run() {
                World world = Bukkit.getWorld(worldId);
                if (world == null) {
                    this.cancel();
                    dispatched.complete(null);
                    return;
                }
                while (index < items.size() && inFlight < maxInFlight) {
                    T item = items.get(index);
                    index++;
                    int[] chunkCoords = chunkOf.apply(item);
                    inFlight++;
                    world.getChunkAtAsync(chunkCoords[0], chunkCoords[1]).whenComplete((chunk, failure) -> {
                        // Counted down whatever happened, or one failed load would keep this task alive forever.
                        inFlight--;
                        if (failure != null || chunk == null) {
                            plugin.getLogger().log(Level.WARNING, "A chunk could not be loaded for an auto-vault sweep: "
                                    + chunkCoords[0] + "," + chunkCoords[1], failure);
                            return;
                        }
                        try {
                            work.run(world, chunk, item);
                        } catch (RuntimeException ex) {
                            plugin.getLogger().log(Level.SEVERE, "An auto-vault sweep failed in chunk "
                                    + chunkCoords[0] + "," + chunkCoords[1], ex);
                        }
                    });
                }
                if (index >= items.size() && inFlight == 0) {
                    this.cancel();
                    dispatched.complete(null);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return dispatched;
    }

    @FunctionalInterface
    private interface ChunkWork<T> {
        void run(@NotNull World world, @NotNull Chunk chunk, @NotNull T item);
    }

    /** Cancels all pending sweeps. Call from {@code onDisable}. */
    public void shutdown() {
        pending.values().forEach(BukkitTask::cancel);
        pending.clear();
    }
}
