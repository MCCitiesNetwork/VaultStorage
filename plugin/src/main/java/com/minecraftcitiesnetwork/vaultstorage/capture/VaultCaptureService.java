package com.minecraftcitiesnetwork.vaultstorage.capture;

import org.bukkit.Chunk;
import org.bukkit.entity.ItemFrame;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.names.UniqueIdentifierResolver;
import net.kyori.adventure.text.Component;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.function.Consumer;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager.Mode;

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
        try {
            this.plugin.bolt().removeProtection(block);
        } catch (RuntimeException ex) {
            this.plugin.getLogger().log(Level.WARNING, "Could not remove the Bolt protection at "
                    + ContainerBlocks.describe(block) + ".", ex);
        }
    }

    /**
     * Stores the container as a vault for {@code owner}, then removes it from the world. Call on
     * the main thread; the future completes there and never exceptionally.
     */
    @NotNull CompletableFuture<CaptureResult> storeThenRemove(@NotNull Block block, @NotNull UUID owner,
                                                                    @NotNull List<Block> otherHalves) {
        CapturePipeline<Block> pipeline = new CapturePipeline<>(
                CaptureStore.inNewVault(plugin.repository(), plugin.claimReleases()),
                new BukkitContainerAccess(plugin.bolt(), owner, otherHalves, plugin.getLogger()),
                plugin.databaseExecutor(), plugin.mainThread(),
                plugin.getLogger());
        return pipeline.capture(block, owner);
    }

    /** Tells the actor the capture stopped because the owner's id could not be checked, and logs where. */
    void ownerUnknown(@NotNull Player actor, @NotNull Block at) {
        actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_OWNER_UNKNOWN));
        plugin.getLogger().warning("Capture by " + actor.getName() + " at " + ContainerBlocks.describe(at)
                + " stopped: the owner's id could not be checked.");
    }

    /**
     * Tells the actor how a capture went. One that stored a vault is also told to its owner and
     * to listeners. Main thread.
     *
     * @param at the container, or the block a hanging entity was on
     * @return whether a vault was stored
     */
    boolean report(@NotNull Player actor, @NotNull CaptureResult result, @NotNull Block at, @NotNull UUID owner) {
        switch (result) {
            case CaptureResult.Vaulted vaulted -> {
                actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_CAPTURED_OK));
                mailOwner(at, actor.getUniqueId(), owner);
                new PlayerVaultEvent(actor, vaulted.vault(), owner, at.getLocation()).callEvent();
                return true;
            }
            case CaptureResult.ChangedMeanwhile changed ->
                    actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_CHANGED_MEANWHILE));
            case CaptureResult.Failed failed ->
                    actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_SAVE_FAILED));
        }
        return false;
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
                ownerUnknown(actor, block);
                onDoneMain.accept(false);
                return;
            }

            storeThenRemove(block, validatedOwner, outcome.reProtectedHalves())
                    .thenAccept(result -> onDoneMain.accept(report(actor, result, block, validatedOwner)));
        }, plugin.mainThread())
                .exceptionally(failure -> {
                    // The caller is waiting behind a loading dialog; it must always hear back.
                    plugin.getLogger().log(Level.SEVERE, "Capture failed for " + actor.getName()
                            + "; the container was left in place.", failure);
                    plugin.mainThread().execute(() -> {
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
        if (!HangingVaultSupport.isSupported(hang)) {
            return CompletableFuture.completedFuture(false);
        }
        Executor mainThread = this.plugin.mainThread();
        return this.owners.validate(decision.containerOwner(), actor)
                .thenComposeAsync(owner -> {
                    if (owner == null) {
                        ownerUnknown(actor, supporting);
                        return CompletableFuture.completedFuture(false);
                    }
                    return storeThenRemove(hang, owner).thenApply(result -> report(actor, result, supporting, owner));
                }, mainThread)
                .exceptionally(failure -> {
                    this.plugin.getLogger().log(Level.SEVERE, "Hanging capture failed for " + actor.getName()
                            + "; the entity was left in place.", failure);
                    mainThread.execute(() -> actor.sendMessage(this.plugin.text().of(MessageKeys.CAPTURE_SAVE_FAILED)));
                    return false;
                });
    }

    /**
     * Stores an item frame or painting for {@code owner}, in a vault of theirs with room, then
     * removes it from the world. Call on the main thread; the future completes there and never
     * exceptionally.
     */
    private @NotNull CompletableFuture<CaptureResult> storeThenRemove(@NotNull Hanging hang, @NotNull UUID owner) {
        return new CapturePipeline<>(
                CaptureStore.inVaultWithRoom(plugin.repository(), HangingVaultSupport.MAX_VAULT_SLOTS),
                new HangingAccess(), plugin.databaseExecutor(), plugin.mainThread(), plugin.getLogger())
                .capture(hang, owner);
    }

    /**
     * A capture with no player and no policy: the caller has decided what to vault and for whom.
     * Call on the main thread. The future says whether a vault was stored.
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
            plugin.getLogger().warning("Offline capture at " + ContainerBlocks.describe(block)
                    + " stopped: the vault owner's id is not valid: " + finalOwner);
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
                                                                          @NotNull Runnable onVaulted) {
        if (!HangingVaultSupport.isSupported(hang)) {
            return CompletableFuture.completedFuture(false);
        }
        // Held until the entity is dealt with. The sweep only loaded this chunk for a moment, and
        // an entity in an unloaded chunk can be neither checked nor removed.
        Runnable release = this.chunks.hold(hang.getLocation().getChunk());
        return storeThenRemove(hang, boltOwner).whenComplete((result, failure) -> release.run())
                .thenApply(result -> {
                    // A capture that did not go through is logged by the pipeline; the entity
                    // stays for the next sweep.
                    if (!(result instanceof CaptureResult.Vaulted)) {
                        return false;
                    }
                    onVaulted.run();
                    return true;
                });
    }
}
