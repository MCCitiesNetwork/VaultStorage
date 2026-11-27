package com.minecraftcitiesnetwork.vaultstorage.capture;

import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.CompletableFuture;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager.Mode;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/**
 * One player's capture mode: a right-click vaults what was clicked, a left-click ends the mode.
 * Registered while the mode lasts; {@link VaultCaptureService} does the work.
 */
public final class CaptureSession implements Listener {

    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    /** A left-click this soon after a capture is another plugin's echo of the right-click, not a cancel. */
    private static final long CANCEL_COOLDOWN_MILLIS = 100L;

    private final @NotNull VaultStoragePlugin plugin;
    private final @NotNull VaultCaptureService service;
    private final @NotNull Player actor;
    private final VaultSessionManager.@NotNull Session session;
    /** True from a click until its capture has finished, so that one block is not captured twice. */
    private boolean busy;
    private long cancelCooldownUntil;

    CaptureSession(@NotNull VaultStoragePlugin plugin, @NotNull VaultCaptureService service, @NotNull Player actor,
                   VaultSessionManager.@NotNull Session session) {
        this.plugin = plugin;
        this.service = service;
        this.actor = actor;
        this.session = session;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (!event.getPlayer().getUniqueId().equals(actor.getUniqueId())) return;

        // Ignore off-hand events to prevent duplicate processing
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;

        // Verify session is still in CAPTURE mode (may have been changed externally)
        if (session.mode() != Mode.CAPTURE) return;

        Action action = event.getAction();

        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            // Prevent cancellation during cooldown (protects against phantom events after capture)
            long now = System.currentTimeMillis();
            if (now < cancelCooldownUntil) {
                event.setCancelled(true);
                return; // Ignore this left-click, still in cooldown
            }
            event.setCancelled(true);
            session.dynamicListener().stop();
            session.clearActionBarTask();
            session.switchTo(Mode.NONE);
            actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_CAPTURE_CANCELLED));
            return;
        }

        if (action != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);
        Block block = event.getClickedBlock();
        if (block == null) return;
        if (busy) return;
        busy = true;

        // Set cooldown to prevent phantom left-click events from canceling capture (~2 ticks / 100ms)
        cancelCooldownUntil = System.currentTimeMillis() + CANCEL_COOLDOWN_MILLIS;

        Block captureBlock = containerFor(block);
        if (captureBlock == null) {
            busy = false;
            return;
        }
        boolean clickedSign = ContainerBlocks.isSign(block);
        // A module may know whose a container nobody locked is, and may have to read a database
        // to say. The rest of the click waits for the answer; with nothing to ask, it runs now.
        plugin.containerOwners().ownerOf(captureBlock)
                .thenCompose(owner -> capture(captureBlock, clickedSign))
                .whenCompleteAsync((done, failure) -> finish(failure), plugin.mainThread());
    }

    /** Ends a click, however it went. Main thread. */
    private void finish(@Nullable Throwable failure) {
        busy = false;
        if (failure != null) {
            plugin.getLogger().log(Level.SEVERE, "Capture failed for " + actor.getName()
                    + "; the container was left in place.", failure);
            actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_SAVE_FAILED));
        }
    }

    /**
     * The block a click is about: the container behind a sign, or the block itself. A sign that
     * is nobody's attachment loses its own lock on the way. Null when a sign has no container.
     */
    private @Nullable Block containerFor(@NotNull Block block) {
        boolean clickedSign = ContainerBlocks.isSign(block);
        boolean clickedAttachment = clickedSign && plugin.attachments().isAttachment(block);

        if (clickedAttachment) {
            // An attached sign stands for its container and goes when the container does.
            return ContainerBlocks.attachedContainer(block);
        }
        if (clickedSign) {
            VaultCapturePolicy.Decision signDecision = plugin.capturePolicy().evaluate(actor, block);
            UUID signOwner = signDecision.containerOwner();
            if (signDecision.allowed()) {
                VaultCaptureService.CaptureOutcome signOutcome = service.prepare(block, signOwner, actor.getUniqueId(), actor::sendMessage);
                if (signOutcome.protectionRemoved()) {
                    actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_SIGN_UNLOCKED));
                }
            } else {
                actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_SIGN_NOT_ALLOWED));
            }

            return ContainerBlocks.attachedContainer(block);
        }
        return block;
    }

    /**
     * Applies the policy to the container and vaults it, unlocks it, or says why not. Main
     * thread. The future completes when the click is dealt with.
     */
    private @NotNull CompletableFuture<Void> capture(@NotNull Block captureBlock, boolean clickedSign) {
        if (!actor.isOnline() || session.mode() != Mode.CAPTURE) {
            // They left, or left capture mode, while the owner was being looked up.
            return DONE;
        }
        VaultCapturePolicy.Decision decision = plugin.capturePolicy().evaluate(actor, captureBlock);
        if (!decision.allowed()) {
            refuse(captureBlock, clickedSign);
            return DONE;
        }
        UUID originalOwner = decision.containerOwner();
        VaultCaptureService.CaptureOutcome outcome = service.prepare(captureBlock, originalOwner, actor.getUniqueId(), actor::sendMessage);
        if (outcome.empty()) {
            // Without a removed protection, prepare has already said why (the other half goes first).
            if (outcome.protectionRemoved()) {
                removeAttachments(captureBlock);
                actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_EMPTY_CAPTURE_SKIPPED));
            }
            return DONE;
        }
        return service.owners().validate(outcome.finalOwner(), actor).thenComposeAsync(owner -> {
            if (owner == null) {
                service.ownerUnknown(actor, captureBlock);
                return DONE;
            }
            return service.storeThenRemove(captureBlock, owner, outcome.reProtectedHalves())
                    .thenAccept(result -> service.report(actor, result, captureBlock, owner));
        }, plugin.mainThread());
    }

    /**
     * Tells the actor they may not vault the container. What is attached to an empty container goes
     * first when the actor holds the override: region membership alone does not remove one.
     */
    private void refuse(@NotNull Block captureBlock, boolean clickedSign) {
        if (ContainerBlocks.isEmpty(captureBlock)
                && plugin.capturePolicy().mayRemoveAttachments(actor) && removeAttachments(captureBlock)) {
            return;
        }
        actor.sendMessage(plugin.text().of(clickedSign
                ? MessageKeys.CAPTURE_CONTAINER_NOT_ALLOWED : MessageKeys.CAPTURE_NOT_ALLOWED));
    }

    /** Removes what is attached to the container and says so. False when nothing is. */
    private boolean removeAttachments(@NotNull Block captureBlock) {
        if (!service.removeAttachments(actor, captureBlock)) {
            return false;
        }
        actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_ATTACHMENT_REMOVED));
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        if (!event.getPlayer().getUniqueId().equals(actor.getUniqueId())) return;
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        if (session.mode() != Mode.CAPTURE) return;
        if (busy) return;

        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof ItemFrame) && !(clicked instanceof Painting)) return;

        event.setCancelled(true);

        busy = true;
        cancelCooldownUntil = System.currentTimeMillis() + CANCEL_COOLDOWN_MILLIS;
        service.captureHanging(actor, (Hanging) clicked).whenCompleteAsync((vaulted, failure) -> finish(failure), plugin.mainThread());
    }
}
