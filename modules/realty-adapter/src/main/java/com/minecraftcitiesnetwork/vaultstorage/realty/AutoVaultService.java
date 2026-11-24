package com.minecraftcitiesnetwork.vaultstorage.realty;

import org.bukkit.block.Container;
import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import com.minecraftcitiesnetwork.vaultstorage.capture.MarkedContainer;
import com.minecraftcitiesnetwork.vaultstorage.capture.ContainerOwnerSource;
import com.minecraftcitiesnetwork.vaultstorage.capture.ContainerAttachments;
import org.bukkit.block.Block;
import java.util.Set;
import java.util.HashSet;
import com.minecraftcitiesnetwork.vaultstorage.capture.ContainerBlocks;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCaptureService;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.region.WorldGuardService;
import com.minecraftcitiesnetwork.vaultstorage.realty.OfflineRegionScanner.DisplacedContainer;
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
import java.util.function.Consumer;
import java.util.function.Supplier;
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
    private final Supplier<AutoVaultSettings> settings;
    private final MessageContainer messages;
    // Main-thread only: the last write to the stored sweeps, which the next one follows.
    private CompletableFuture<Void> storeWrites = CompletableFuture.completedFuture(null);
    // Main-thread only: the regions that have changed hands since the module started.
    private final Set<RegionKey> submitted = new HashSet<>();

    /** @param messages the module's own messages.yml, reloaded in place */
    public AutoVaultService(@NotNull VaultStoragePlugin plugin, @NotNull PendingSweepStore store,
                            @NotNull Supplier<AutoVaultSettings> settings, @NotNull MessageContainer messages) {
        this.messages = messages;
        this.plugin = plugin;
        this.store = store;
        this.settings = settings;
    }

    /**
     * Schedules every sweep that was still waiting when the server last stopped. One that came
     * due meanwhile runs shortly after startup.
     */
    public void resume() {
        // The first of the store's writes and reads, so a sweep saved meanwhile is read with the rest.
        CompletableFuture<List<PendingSweep>> read =
                this.storeWrites.thenApplyAsync(start -> this.store.all(), this.plugin.databaseExecutor());
        this.storeWrites = read.handle((sweeps, failure) -> null);
        read.thenAcceptAsync(sweeps -> {
            Instant now = Instant.now();
            int resumed = 0;
            for (PendingSweep sweep : sweeps) {
                // A change of hands since startup has a newer sweep of the region, waiting or run.
                if (!this.submitted.contains(sweep.key())) {
                    schedule(sweep, sweep.ticksUntilDue(now));
                    resumed++;
                }
            }
            if (resumed > 0) {
                this.plugin.getLogger().info("Resumed " + resumed + " auto-vault sweep(s) from before the restart.");
            }
        }, this.plugin.mainThread()).exceptionally(failure -> {
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
        this.submitted.add(sweep.key());
        // Stored so that a restart during the grace period does not lose the sweep.
        storeWrite(() -> this.store.save(sweep), failure ->
                this.plugin.getLogger().log(Level.SEVERE, "Could not store the pending sweep of " + regionId
                        + "; it will be lost if the server restarts before it runs.", failure));
        schedule(sweep, delayTicks);
    }

    /**
     * Main thread. Writes to the stored sweeps one after the other, in the order asked for: on
     * the pool alone, a sweep's delete could overtake its own save.
     */
    private void storeWrite(@NotNull Runnable write, @NotNull Consumer<Throwable> onFailure) {
        this.storeWrites = this.storeWrites
                .thenRunAsync(write, this.plugin.databaseExecutor())
                .exceptionally(failure -> {
                    onFailure.accept(failure);
                    return null;
                });
    }

    /** Main thread. Replaces a sweep already waiting for the same region. */
    private void schedule(@NotNull PendingSweep sweep, long delayTicks) {
        RegionKey key = sweep.key();
        BukkitTask task = new BukkitRunnable() {
            @Override public void run() {
                // A fired task is always the current pending entry (a resubmit cancels the prior one).
                pending.remove(key);
                runSweep(sweep);
            }
        }.runTaskLater(plugin, delayTicks);
        BukkitTask previous = pending.put(key, task);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void runSweep(@NotNull PendingSweep stored) {
        RegionKey key = stored.key();
        UUID initiator = stored.initiatorUuid();
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
        VaultRegion region = wgs.getRegionById(key.regionId(), world);
        if (region == null) {
            // Region was deleted during the delay window; nothing to sweep.
            forget(stored);
            return;
        }

        // Asked for each candidate, so a region counts by its shape and not by its bounding box.
        // getBlockAt does not load a chunk, and the lookup needs only the position.
        SweepFilter filter = SweepFilter.forSweep(region, initiator,
                (x, y, z) -> wgs.getRegionsAt(world.getBlockAt(x, y, z)));
        Sweep sweep = new Sweep();

        List<DisplacedContainer> displaced = OfflineRegionScanner.scan(plugin.bolt(), key.worldId(), region.boundingBox(), filter);
        CompletableFuture<Void> blocksDispatched = displaced == null || displaced.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : vaultBlocks(key.worldId(), displaced, initiator, sweep);
        CompletableFuture<Void> hangingsDispatched =
                this.settings.get().includeHangings()
                        ? vaultHangings(key.worldId(), region.boundingBox(), filter, sweep)
                        : CompletableFuture.completedFuture(null);

        CompletableFuture<Void> unlockedDispatched =
                vaultUnlocked(world, region.boundingBox(), filter, initiator, sweep);

        // Every capture has been started once the passes have visited their chunks; the sweep is
        // over when the last of them has finished.
        CompletableFuture.allOf(blocksDispatched, hangingsDispatched, unlockedDispatched)
                .thenCompose(dispatched -> sweep.allWork())
                .whenCompleteAsync((done, failure) -> {
                    if (failure != null) {
                        // Left stored: a sweep cut short, by a stop for one, runs again after a restart.
                        plugin.getLogger().log(Level.SEVERE, "The auto-vault sweep of " + key.regionId()
                                + " failed. It will run again after a restart.", failure);
                    }
                    // One message per previous owner, however many of their containers were vaulted.
                    sweep.vaultedPerOwner().forEach((owner, count) ->
                            plugin.ownerNotifications().sweepVaulted(owner, initiator, count, key.regionId()));
                    Player occupant = Bukkit.getPlayer(initiator);
                    if (!sweep.vaultedPerOwner().isEmpty() && occupant != null && this.settings.get().notifyOccupant()) {
                        occupant.sendMessage(this.messages.messageFor(RealtyMessages.OCCUPANT_NOTIFIED));
                    }
                    if (failure == null) {
                        forget(stored);
                    }
                }, plugin.mainThread());
    }

    /** Removes the stored sweep: it has run, or has nothing left to do. */
    private void forget(@NotNull PendingSweep sweep) {
        storeWrite(() -> this.store.delete(sweep), failure ->
                this.plugin.getLogger().log(Level.WARNING, "Could not remove the finished sweep of " + sweep.regionId()
                        + "; it will run again after a restart.", failure));
    }

    /** What one sweep has started and what came of it. Main thread only. */
    private static final class Sweep {
        private final List<CompletableFuture<?>> work = new ArrayList<>();
        private final Map<UUID, Integer> vaultedPerOwner = new HashMap<>();

        /** Has the sweep wait for this. Call before the pass that started it says it is done. */
        void track(@NotNull CompletableFuture<?> started) {
            this.work.add(started);
        }

        /** Counts the capture for the owner if it stores a vault. Captures end on the main thread. */
        @NotNull CompletableFuture<Void> counted(@NotNull UUID owner, @NotNull CompletableFuture<Boolean> capture) {
            return capture.thenAccept(vaulted -> {
                if (vaulted) {
                    this.vaultedPerOwner.merge(owner, 1, Integer::sum);
                }
            });
        }

        @NotNull CompletableFuture<Void> allWork() {
            return CompletableFuture.allOf(this.work.toArray(new CompletableFuture[0]));
        }

        @NotNull Map<UUID, Integer> vaultedPerOwner() {
            return this.vaultedPerOwner;
        }
    }

    /** Vaults each displaced container, loading its chunk asynchronously. */
    private @NotNull CompletableFuture<Void> vaultBlocks(@NotNull UUID worldId, @NotNull List<DisplacedContainer> displaced,
                                                        @NotNull UUID initiator, @NotNull Sweep sweep) {
        return forEachChunkPaced(worldId, displaced,
                dc -> new int[]{dc.x() >> 4, dc.z() >> 4},
                // Tolerant of blocks changed since the scan (non-containers just drop their protection).
                // Bolt keeps one lock for a double chest, so the scan found it once.
                (world, chunk, dc) -> sweep.track(captureInOrder(
                        halvesInCaptureOrder(world.getBlockAt(dc.x(), dc.y(), dc.z())), initiator, dc.owner(), sweep)));
    }

    /**
     * Captures the blocks one after the other for the owner, counting each vault. Main thread,
     * with their chunk loaded. A capture holds the chunk itself and ends on the main thread,
     * where the next starts at once.
     */
    private @NotNull CompletableFuture<Void> captureInOrder(@NotNull List<Block> blocks, @NotNull UUID initiator,
                                                           @NotNull UUID owner, @NotNull Sweep sweep) {
        VaultCaptureService captures = plugin.captureService();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Block block : blocks) {
            chain = chain.thenCompose(previous ->
                    sweep.counted(owner, captures.captureOfflineAsync(block, initiator, owner)));
        }
        return chain;
    }

    /**
     * The block, and with it the other half if it is half of a double chest. A half with items
     * comes first: an empty half is only unlocked, and must not be while the other still has to
     * be captured.
     */
    private static @NotNull List<Block> halvesInCaptureOrder(@NotNull Block block) {
        if (!(block.getState() instanceof Container container)) {
            return List.of(block);
        }
        List<Block> others = ContainerBlocks.otherHalves(block, container);
        if (others.isEmpty()) {
            return List.of(block);
        }
        Block other = others.getFirst();
        return ContainerBlocks.isEmpty(block) && !ContainerBlocks.isEmpty(other)
                ? List.of(other, block) : List.of(block, other);
    }

    /**
     * Vaults displaced item frames and paintings across the region. Entity protections are not indexed
     * by coordinate, so every chunk in the region's bounds is loaded and its hanging entities inspected.
     */
    private @NotNull CompletableFuture<Void> vaultHangings(@NotNull UUID worldId, @NotNull BoundingBox box,
                                                          @NotNull SweepFilter filter, @NotNull Sweep sweep) {
        VaultCaptureService captureService = plugin.captureService();
        BoltService bolt = plugin.bolt();

        List<int[]> chunks = chunksOf(box);

        return forEachChunkPaced(worldId, chunks,
                coords -> coords,
                (world, chunk, coords) -> {
                    for (Entity entity : chunk.getEntities()) {
                        if (!(entity instanceof ItemFrame) && !(entity instanceof Painting)) continue;
                        UUID owner = bolt.getOwner(entity);
                        if (owner == null || !filter.shouldVault(owner, entity.getX(), entity.getY(), entity.getZ())) continue;
                        sweep.track(sweep.counted(owner,
                                captureService.captureHangingOfflineAsync((Hanging) entity, owner)));
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
     * Deals with displaced players' containers that nobody locked but that a module can name
     * an owner for: the pass over Bolt protections cannot see those. A stocked container is
     * vaulted to that owner; an empty one stays and only what is attached to it goes. Does
     * nothing when no module names owners.
     *
     * <p>A container with a Bolt owner is left to the Bolt pass, which has already decided about
     * it.
     */
    private @NotNull CompletableFuture<Void> vaultUnlocked(@NotNull World world, @NotNull BoundingBox box,
                                                          @NotNull SweepFilter filter, @NotNull UUID initiator,
                                                          @NotNull Sweep sweep) {
        ContainerOwnerSource owners = plugin.integrations().ownerSources();
        UUID worldId = world.getUID();
        String worldName = world.getName();
        // One chunk at a time: a source may read a database, on the pool player-facing reads use.
        List<MarkedContainer> marked = new ArrayList<>();
        CompletableFuture<Void> reads = CompletableFuture.completedFuture(null);
        for (int[] chunk : chunksOf(box)) {
            reads = reads.thenCompose(previous -> owners.ownedIn(worldName, chunk[0], chunk[1]))
                    // Each read ends before the next begins, so the list is never added to by two.
                    .thenAccept(marked::addAll);
        }
        return reads.thenComposeAsync(read -> {
            // Only what is inside the swept region and belongs to a displaced player.
            List<MarkedContainer> candidates = marked.stream()
                    .filter(entry -> filter.shouldVault(entry.owner(), entry.x(), entry.y(), entry.z()))
                    .toList();
            // Two markers on one container must not start two captures of it.
            Set<Block> handled = new HashSet<>();
            return forEachChunkPaced(worldId, candidates,
                    entry -> new int[]{entry.chunkX(), entry.chunkZ()},
                    (loaded, chunk, entry) -> sweepUnlocked(loaded, chunk, entry, handled, filter, initiator, sweep));
        }, plugin.mainThread());
    }

    /** Main thread, with the marker's chunk loaded. */
    private void sweepUnlocked(@NotNull World world, @NotNull Chunk chunk, @NotNull MarkedContainer marked,
                               @NotNull Set<Block> handled, @NotNull SweepFilter filter,
                               @NotNull UUID initiator, @NotNull Sweep sweep) {
        ContainerAttachments attachments = plugin.attachments();
        Block marker = world.getBlockAt(marked.x(), marked.y(), marked.z());
        if (!attachments.isAttachment(marker)) {
            // The module's records are behind the world: the marker has gone since.
            return;
        }
        Block container = ContainerBlocks.attachedContainer(marker);
        if (container == null || handled.contains(container)) {
            return;
        }
        // Both halves of a double chest are one container to its owner, whichever the marker is on.
        List<Block> both = halvesInCaptureOrder(container);
        handled.addAll(both);
        if (both.stream().anyMatch(half -> plugin.bolt().getOwner(half) != null)) {
            // Locked: the Bolt pass has already decided about it.
            return;
        }
        VaultCaptureService captures = plugin.captureService();
        // The owner may take a database read to settle; the chunk must still be there afterwards.
        Runnable release = captures.holdChunk(chunk);
        CompletableFuture<Void> done = plugin.containerOwners().ownerOf(container).thenCompose(owner -> {
            if (owner.isEmpty()
                    || !filter.shouldVault(owner.get(), container.getX(), container.getY(), container.getZ())) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            // Read again now: the answer may have taken a moment. A stocked half is vaulted, and
            // the plugin that keeps the attachments sees it go and tidies up itself.
            List<Block> halves = halvesInCaptureOrder(container);
            return captureInOrder(halves, initiator, owner.get(), sweep).thenRun(() -> {
                // What is left holds nothing to store. It is still the displaced player's, so
                // what is attached to it goes. Not while a half that could not be vaulted stands.
                if (halves.stream().allMatch(ContainerBlocks::isEmpty)) {
                    for (Block half : halves) {
                        if (!half.getType().isAir()) {
                            captures.removeAttachments(null, half);
                        }
                    }
                }
            });
        });
        sweep.track(done.whenCompleteAsync((finished, failure) -> release.run(), plugin.mainThread()));
    }

    /**
     * Runs {@code work} for each item, loading the chunk given by {@code chunkOf} asynchronously and keeping
     * at most {@code scan.batch-size} loads in flight. {@code work} runs on the main thread once that chunk is
     * loaded. Stops if the world unloads.
     */
    private <T> @NotNull CompletableFuture<Void> forEachChunkPaced(@NotNull UUID worldId, @NotNull List<T> items,
                                                                  @NotNull Function<T, int[]> chunkOf,
                                                                  @NotNull ChunkWork<T> work) {
        final int maxInFlight = Math.max(1, plugin.settings().scan().batchSize());
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
