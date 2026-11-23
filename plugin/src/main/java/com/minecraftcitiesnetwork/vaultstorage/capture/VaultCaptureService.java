package com.minecraftcitiesnetwork.vaultstorage.capture;

import org.bukkit.Chunk;
import org.bukkit.entity.ItemFrame;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.capture.PlayerVaultEvent;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.vault.ItemSerialization;
import com.minecraftcitiesnetwork.vaultstorage.names.UniqueIdentifierResolver;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import com.minecraftcitiesnetwork.vaultstorage.database.AppendResult;
import com.minecraftcitiesnetwork.vaultstorage.database.NewVault;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultItem;
import java.util.function.Consumer;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCapturePolicy;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager.Mode;
import com.minecraftcitiesnetwork.vaultstorage.capture.HangingVaultSupport;

/**
 * Service responsible for capturing a block into a Vault model.
 * <p>Behavior expansion: non-container blocks can now be "vaulted". They behave identically to an empty container capture:
 * Bolt protection is removed, no vault is created, the block remains in place.</p>
 * Threading policy:
 * - Must run on the main server thread because it interacts with the world (block state and inventories).
 *
 */
public final class VaultCaptureService {

    private final @NotNull VaultStoragePlugin plugin;
    private final @NotNull CaptureOwners owners;
    private final @NotNull ChunkHolds chunks;

    public VaultCaptureService(@NotNull VaultStoragePlugin plugin) {
        this.plugin = plugin;
        this.owners = new CaptureOwners(plugin.getLogger(), plugin::names);
        this.chunks = new ChunkHolds(plugin);
    }

    @NotNull CaptureOwners owners() {
        return this.owners;
    }

    /**
     * Immutable outcome produced by {@link #captureWithDoubleChestSupport(Player, Block, UUID)}.
     * Contract:
     * <ul>
     * <li>If {@code empty} is true: no block removed, no vault created.</li>
     * <li>{@code protectionRemoved}: true if Bolt protection was stripped, false if action was canceled/warned.</li>
     * </ul>
     */
    public record CaptureOutcome(boolean empty, boolean protectionRemoved, @Nullable UUID originalOwner, @NotNull UUID finalOwner, @NotNull List<Block> reProtectedHalves) {}

    /**
     * Decides what a capture does with the block, and does the part that stores nothing: an
     * empty block only loses its protection. A block with items is left untouched for
     * {@link #storeThenRemove}.
     *
     * @param originalOwner      the lock's owner, or null when the block has none
     * @param finalOwnerFallback who owns the vault when the block has no lock owner
     * @param warn               told when nothing was done and why; null for an automated capture
     */
    @NotNull CaptureOutcome prepare(@NotNull Block block, @Nullable UUID originalOwner, @NotNull UUID finalOwnerFallback,
                                    @Nullable Consumer<Component> warn) {
        UUID finalOwner = originalOwner != null ? originalOwner : finalOwnerFallback;
        boolean isContainer = block.getState() instanceof Container;
        List<Block> otherHalves = block.getState() instanceof Container container
                ? ContainerBlocks.otherHalves(block, container) : List.of();
        DoubleChestRule.Action action = DoubleChestRule.decide(isContainer,
                !isContainer || ContainerBlocks.isEmpty(block), !otherHalves.isEmpty(),
                otherHalves.isEmpty() || ContainerBlocks.isEmpty(otherHalves.getFirst()));
        return switch (action) {
            case UNPROTECT -> {
                // Bolt keeps one protection for a double chest, so this covers both halves.
                unprotect(block);
                yield new CaptureOutcome(true, true, originalOwner, finalOwner, List.of());
            }
            case OTHER_HALF_FIRST -> {
                if (warn != null) {
                    warn.accept(this.plugin.text().of(MessageKeys.CAPTURE_DOUBLE_CHEST_ORDER));
                }
                yield new CaptureOutcome(true, false, originalOwner, finalOwner, List.of());
            }
            case CAPTURE -> new CaptureOutcome(false, false, originalOwner, finalOwner, List.copyOf(otherHalves));
        };
    }

    /** Removes the block's Bolt protection. A failure is logged and the block stays protected. */
    void unprotect(@NotNull Block block) {
        BoltService bolt = this.plugin.bolt();
        if (bolt == null) {
            return;
        }
        try {
            bolt.removeProtection(block);
        } catch (RuntimeException ex) {
            this.plugin.getLogger().log(Level.WARNING, "Could not remove the Bolt protection at "
                    + block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ() + ".", ex);
        }
    }

    /**
     * Stores the container as a vault for {@code owner}, then removes it from the world. Call on
     * the main thread; the future completes there and never exceptionally.
     */
    @NotNull CompletableFuture<CaptureResult> storeThenRemove(@NotNull Block block, @NotNull UUID owner,
                                                                    @NotNull List<Block> otherHalves) {
        CapturePipeline<Block> pipeline = new CapturePipeline<>(plugin.repository(),
                new BukkitContainerAccess(plugin.bolt(), owner, otherHalves, plugin.getLogger()),
                plugin.databaseExecutor(), plugin.getServer().getScheduler().getMainThreadExecutor(plugin),
                plugin.getLogger());
        return pipeline.capture(block, owner);
    }

    /** Tells the owner their container was vaulted. A failed notification changes nothing else. */
    void mailOwner(@NotNull Block block, @NotNull UUID actor, @NotNull UUID owner) {
        String regionId = plugin.worldGuard().getRegionsAt(block).stream()
                .map(region -> region.id()).findFirst().orElse(null);
        plugin.ownerNotifications().vaultCreated(owner, actor, regionId);
    }

    /**
     * Puts the player in capture mode: a right-click vaults the block or hanging entity clicked,
     * a left-click ends the mode. An action bar says whether what they look at can be vaulted.
     */
    public void startCaptureSession(@NotNull Player actor) {
        if (!actor.hasPermission(Permissions.ACTION_CAPTURE)) {
            actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_NO_PERMISSION));
            return;
        }
        VaultSessionManager.Session session = plugin.sessionManager().getOrCreate(actor.getUniqueId());
        session.switchTo(Mode.CAPTURE);

        if (actor.hasPermission(Permissions.ADMIN)) {
            actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_ADMIN_MODE_ENABLED));
        }

        session.dynamicListener().setListener(new CaptureSession(this.plugin, this, actor, session));
        session.setActionBarTask(new CaptureActionBar(this.plugin, actor).start());

        session.dynamicListener().start();
    }

    /**
     * Unlocks and removes what is attached to a container, through the plugin that keeps each.
     * Main thread.
     *
     * @param actor who is removing them, or null for an automated capture
     */
    public void removeAttachments(@Nullable Player actor, @NotNull List<Block> attached) {
        ContainerAttachments attachments = plugin.attachments();
        for (Block block : attached) {
            unprotect(block);
            attachments.remove(actor, block);
        }
    }

    /**
     * Keeps a chunk loaded until what comes back is run, once and on the main thread. For work
     * on a container that has to wait for an answer before it can capture.
     */
    public @NotNull Runnable holdChunk(@NotNull Chunk chunk) {
        return this.chunks.hold(chunk);
    }

    /**
     * Direct one-shot capture with messages and DB persistence.
     * Applies policy, captures/removes the block, persists items when applicable, and sends user feedback.
     * The callback is invoked on the main thread with true when a vault was persisted (non-empty container), false otherwise.
     */
    public void captureDirectAsync(@NotNull Player actor, @NotNull Block block, @NotNull Consumer<Boolean> onDoneMain) {
        // A scan result's button reaches this without a capture session having checked.
        if (!actor.hasPermission(Permissions.ACTION_CAPTURE)) {
            actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_NO_PERMISSION));
            onDoneMain.accept(false);
            return;
        }
        VaultCapturePolicy.Decision decision = plugin.capturePolicy().evaluate(actor, block);
        UUID originalOwner = decision.containerOwner();
        if (!decision.allowed()) {
            actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_NOT_ALLOWED));
            onDoneMain.accept(false);
            return;
        }
        CaptureOutcome outcome = prepare(block, originalOwner, actor.getUniqueId(), actor::sendMessage);
        if (outcome.empty()) {
            // Update to respect the protectionRemoved flag
            if (outcome.protectionRemoved()) {
                actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_EMPTY_CAPTURE_SKIPPED));
            }
            onDoneMain.accept(false);
            return;
        }
        UUID finalOwner = outcome.finalOwner();

        // The owner is validated first; the rest runs on the main thread, where the pipeline starts.
        this.owners.validate(finalOwner, actor).thenAcceptAsync(validatedOwner -> {
            if (validatedOwner == null) {
                actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_OWNER_UNKNOWN));
                plugin.getLogger().warning(
                    "[VaultCaptureService] Direct vault capture aborted for " + actor.getName() +
                    " at " + block.getWorld().getName() + ":" + block.getX() + "," + block.getY() + "," + block.getZ() +
                    " - could not validate owner UUID"
                );
                onDoneMain.accept(false);
                return;
            }

            storeThenRemove(block, validatedOwner, outcome.reProtectedHalves()).thenAccept(result -> {
                switch (result) {
                    case CaptureResult.Vaulted vaulted -> {
                        actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_CAPTURED_OK));
                        mailOwner(block, actor.getUniqueId(), validatedOwner);
                        new PlayerVaultEvent(actor, vaulted.vault(), validatedOwner, block.getLocation()).callEvent();
                        onDoneMain.accept(true);
                    }
                    case CaptureResult.ChangedMeanwhile changed -> {
                        actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_CHANGED_MEANWHILE));
                        onDoneMain.accept(false);
                    }
                    case CaptureResult.Failed failed -> {
                        actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_SAVE_FAILED));
                        onDoneMain.accept(false);
                    }
                }
            });
        }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
                .exceptionally(failure -> {
                    // The caller is waiting behind a loading dialog; it must always hear back.
                    plugin.getLogger().log(Level.SEVERE, "Capture failed for " + actor.getName()
                            + "; the container was left in place.", failure);
                    plugin.getServer().getScheduler().getMainThreadExecutor(plugin).execute(() -> {
                        actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_SAVE_FAILED));
                        onDoneMain.accept(false);
                    });
                    return null;
                });
    }

    /**
     * Vaults a locked item frame or painting for the player who asked, with what it displays, and
     * tells them how it went. An empty item frame only loses the lock on the block behind it.
     * Call on the main thread; the future completes there, with true when a vault was stored,
     * and never exceptionally.
     */
    public @NotNull CompletableFuture<Boolean> captureHanging(@NotNull Player actor, @NotNull Hanging hang) {
        if (!actor.hasPermission(Permissions.ACTION_CAPTURE)) {
            actor.sendMessage(this.plugin.text().of(MessageKeys.CAPTURE_NO_PERMISSION));
            return CompletableFuture.completedFuture(false);
        }
        Block supporting = HangingVaultSupport.resolveSupportingBlock(hang);
        VaultCapturePolicy.Decision decision = this.plugin.capturePolicy().evaluateHangingCapture(actor, hang, supporting);
        if (!decision.allowed()) {
            actor.sendMessage(this.plugin.text().of(MessageKeys.CAPTURE_NOT_ALLOWED));
            return CompletableFuture.completedFuture(false);
        }
        if (hang instanceof ItemFrame frame && HangingVaultSupport.isItemFrameDisplayEmpty(frame)) {
            unprotect(supporting);
            actor.sendMessage(this.plugin.text().of(MessageKeys.CAPTURE_EMPTY_CAPTURE_SKIPPED));
            return CompletableFuture.completedFuture(false);
        }
        List<ItemStack> stacks = HangingVaultSupport.itemStacksFrom(hang);
        if (stacks.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        Executor mainThread = this.plugin.getServer().getScheduler().getMainThreadExecutor(this.plugin);
        return this.owners.validate(decision.containerOwner(), actor)
                .thenComposeAsync(owner -> {
                    if (owner == null) {
                        actor.sendMessage(this.plugin.text().of(MessageKeys.CAPTURE_OWNER_UNKNOWN));
                        return CompletableFuture.completedFuture(false);
                    }
                    // Stored first, removed after: a failed save leaves the entity where it is.
                    return CompletableFuture
                            .supplyAsync(() -> persistHangingStacks(stacks, owner, supporting), this.plugin.databaseExecutor())
                            .thenApplyAsync(stored -> {
                                if (!removeIfUnchanged(hang, stacks, stored)) {
                                    actor.sendMessage(this.plugin.text().of(MessageKeys.CAPTURE_CHANGED_MEANWHILE));
                                    return false;
                                }
                                actor.sendMessage(this.plugin.text().of(MessageKeys.CAPTURE_CAPTURED_OK));
                                mailOwner(supporting, actor.getUniqueId(), owner);
                                new PlayerVaultEvent(actor, stored.vault(), owner, supporting.getLocation()).callEvent();
                                return true;
                            }, mainThread);
                }, mainThread)
                .exceptionally(failure -> {
                    this.plugin.getLogger().log(Level.SEVERE, "Hanging capture failed for " + actor.getName()
                            + "; the entity was left in place.", failure);
                    mainThread.execute(() -> actor.sendMessage(this.plugin.text().of(MessageKeys.CAPTURE_SAVE_FAILED)));
                    return false;
                });
    }

    /**
     * Offline-safe capture: no live {@link Player} and no policy evaluation; the caller decides what to vault.
     * The vault is owned by {@code boltOwner} (falls back to {@code initiatorUuid} when null) and created by
     * {@code initiatorUuid}. Must be called on the main thread; persistence runs async (fire-and-forget).
     */
    public @NotNull CompletableFuture<Boolean> captureOfflineAsync(@NotNull Block block,
                                    @NotNull UUID initiatorUuid,
                                    @NotNull UUID boltOwner,
                                    @NotNull Runnable onVaulted) {

        CaptureOutcome outcome = prepare(block, boltOwner, initiatorUuid, null);
        if (outcome.empty()) {
            return CompletableFuture.completedFuture(false);
        }

        UUID finalOwner = outcome.finalOwner();
        if (!UniqueIdentifierResolver.isValidUUID(finalOwner)) {
            plugin.getLogger().warning(
                    "[VaultCaptureService] Offline capture aborted at " + block.getWorld().getName() + ":"
                            + block.getX() + "," + block.getY() + "," + block.getZ()
                            + " - invalid vault owner UUID: " + finalOwner);
            return CompletableFuture.completedFuture(false);
        }

        // Held until the container has been checked and removed: the sweep loaded this chunk only
        // for a moment, and the check would otherwise load it again on the main thread.
        Runnable release = this.chunks.hold(block.getChunk());
        return storeThenRemove(block, finalOwner, outcome.reProtectedHalves()).whenComplete((result, failure) -> release.run())
                .thenApply(result -> {
            if (!(result instanceof CaptureResult.Vaulted vaulted)) {
                // Already logged by the pipeline. The container stays for the next sweep.
                return false;
            }
            plugin.getLogger().info("[VaultCaptureService] Offline vault created: ID=" + vaulted.vault()
                    + " owner=" + finalOwner + " initiator=" + initiatorUuid);
            // What was attached to the container goes with it.
            List<Block> attached = plugin.attachments().attachedTo(block);
            if (!attached.isEmpty()) {
                removeAttachments(null, attached);
            }
            // The sweep tells the owner once, at its end; no PlayerVaultEvent for an automated capture.
            onVaulted.run();
            return true;
        });
    }

    /**
     * Offline-safe capture of a Bolt-locked hanging entity (item frame or painting) with no live
     * {@link Player} and no policy evaluation. Its contents (the frame/painting plus any displayed item)
     * are stored in a vault owned by {@code boltOwner}, merged into an existing vault when one has room,
     * and the entity is removed (which also disposes its Bolt protection).
     * <p>Must be called on the main thread with the entity loaded; persistence runs async.</p>
     */
    public @NotNull CompletableFuture<Boolean> captureHangingOfflineAsync(@NotNull Hanging hang, @NotNull UUID boltOwner,
                                           @NotNull UUID initiatorUuid, @NotNull Runnable onVaulted) {
        List<ItemStack> stacks = HangingVaultSupport.itemStacksFrom(hang);
        if (stacks.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        Block supporting = HangingVaultSupport.resolveSupportingBlock(hang);
        Executor mainThread = plugin.getServer().getScheduler().getMainThreadExecutor(plugin);
        // Held until the entity is dealt with. The sweep only loaded this chunk for a moment, and
        // an entity in an unloaded chunk can be neither checked nor removed.
        Runnable release = this.chunks.hold(hang.getLocation().getChunk());
        return CompletableFuture
                .supplyAsync(() -> persistHangingStacks(stacks, boltOwner, supporting), plugin.databaseExecutor())
                .thenApplyAsync(stored -> {
                    if (!removeIfUnchanged(hang, stacks, stored)) {
                        return false;
                    }
                    onVaulted.run();
                    return true;
                }, mainThread)
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.SEVERE, "Offline hanging capture failed at "
                            + supporting.getWorld().getName() + " " + supporting.getX() + "," + supporting.getY() + ","
                            + supporting.getZ() + "; the entity was left in place.", failure);
                    return false;
                })
                .whenCompleteAsync((done, failure) -> release.run(), mainThread);
    }

    /**
     * The check and the removal of the ordering rule, for a hanging entity. Main thread. If the
     * entity has gone, or shows something else than what was stored, the stored copy is taken
     * back and nothing is removed: otherwise its items would exist twice.
     */
    private boolean removeIfUnchanged(@NotNull Hanging hang, @NotNull List<ItemStack> stored, @NotNull AppendResult where) {
        if (hang.isValid() && HangingVaultSupport.itemStacksFrom(hang).equals(stored)) {
            hang.remove();
            return true;
        }
        CompletableFuture.runAsync(() -> this.plugin.repository().undoAppend(where), this.plugin.databaseExecutor())
                .exceptionally(failure -> {
                    this.plugin.getLogger().log(Level.SEVERE, "A hanging entity changed while it was being stored, and"
                            + " the stored copy in vault " + where.vault() + " could not be taken back.", failure);
                    return null;
                });
        return false;
    }

    /**
     * Stores hanging stacks at the end of the owner's oldest vault with room, or in a new vault
     * anchored at {@code supporting}. One transaction, with the chosen vault locked, so two
     * captures for one owner cannot take the same slots. Blocks on the database.
     */
    @NotNull AppendResult persistHangingStacks(@NotNull List<ItemStack> stacks, @NotNull UUID boltOwner,
                                               @NotNull Block supporting) {
        List<VaultItem> items = new ArrayList<>(stacks.size());
        for (int offset = 0; offset < stacks.size(); offset++) {
            ItemStack stack = stacks.get(offset);
            items.add(new VaultItem(offset, stack.getAmount(), ItemSerialization.toBytes(stack)));
        }
        // The stored material must be a block: placement uses Block#setType.
        NewVault ifNoneFits = new NewVault(supporting.getWorld().getUID(), supporting.getWorld().getName(),
                supporting.getX(), supporting.getY(), supporting.getZ(), boltOwner, Material.CHEST.name(), null);
        return plugin.repository().appendItems(boltOwner, items, HangingVaultSupport.MAX_VAULT_SLOTS, ifNoneFits);
    }
}
