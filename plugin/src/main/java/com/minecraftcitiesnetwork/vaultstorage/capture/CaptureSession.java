package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.capture.PlayerVaultEvent;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCapturePolicy;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.ChestShopService;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager.Mode;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager;
import java.util.List;
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
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

/**
 * One player's capture mode: a right-click vaults what was clicked, a left-click ends the mode.
 * Registered while the mode lasts; {@link VaultCaptureService} does the work.
 */
public final class CaptureSession implements Listener {

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

        Block captureBlock = block;
        boolean clickedSign = ContainerBlocks.isSign(block);
        ChestShopService chestShop = plugin.chestShop();
        boolean clickedChestShopSign = clickedSign && chestShop.isShopSign(block);

        // Sequential sign->container policy flow:
        // 1) Evaluate sign (non-container) policy and optionally unlock sign.
        // 2) Evaluate container policy on attached container (if present).
        if (clickedChestShopSign) {
            // Shop sign is handled through its container; it is removed once the container unlocks.
            Block attachedContainer = ContainerBlocks.attachedContainer(block);
            if (attachedContainer == null) {
                busy = false;
                return;
            }
            captureBlock = attachedContainer;
        } else if (clickedSign) {
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

            Block attachedContainer = ContainerBlocks.attachedContainer(block);
            if (attachedContainer == null) {
                busy = false;
                return;
            }
            captureBlock = attachedContainer;
        }

        VaultCapturePolicy.Decision decision = plugin.capturePolicy().evaluate(actor, captureBlock);
        UUID originalOwner = decision.containerOwner();
        if (!decision.allowed()) {
            // A shop sign on an empty container that cannot be captured is removed only with the
            // override permission. Region membership alone let any member remove anyone's shop sign.
            if (chestShop.isAvailable() && ContainerBlocks.isEmpty(captureBlock)) {
                List<Block> shopSigns = chestShop.findShopSigns(captureBlock);
                if (!shopSigns.isEmpty()
                        && plugin.capturePolicy().evaluateChestShopSignRemoval(actor, captureBlock).allowed()) {
                    service.removeShopSigns(actor, shopSigns);
                    actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_CHEST_SHOP_SIGN_REMOVED));
                    busy = false;
                    return;
                }
            }
            actor.sendMessage(plugin.text().of(clickedSign ? MessageKeys.CAPTURE_CONTAINER_NOT_ALLOWED : MessageKeys.CAPTURE_NOT_ALLOWED));
            busy = false;
            return;
        }

        VaultCaptureService.CaptureOutcome outcome = service.prepare(captureBlock, originalOwner, actor.getUniqueId(), actor::sendMessage);
        if (outcome.empty()) {
            // Only send "Protection removed" if we actually removed protection.
            // If protectionRemoved is false, it means we sent a warning (e.g., "Vault the other side first").
            if (outcome.protectionRemoved()) {
                // Drop any shop sign left on the now-unlocked container.
                if (chestShop.isAvailable()) {
                    List<Block> shopSigns = chestShop.findShopSigns(captureBlock);
                    if (!shopSigns.isEmpty()) {
                        service.removeShopSigns(actor, shopSigns);
                        actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_CHEST_SHOP_SIGN_REMOVED));
                    }
                }
                actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_EMPTY_CAPTURE_SKIPPED));
            }
            busy = false;
            return;
        }

        if (originalOwner == null && decision.hasOverride()) {
            actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_NO_BOLT_OWNER));
        }

        final Block finalCaptureBlock = captureBlock;
        UUID finalOwner = outcome.finalOwner();

        service.owners().validate(finalOwner, actor).thenAcceptAsync(validatedOwner -> {
            if (validatedOwner == null) {

                new BukkitRunnable(){

                    @Override
                    public void run() {
                        actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_OWNER_UNKNOWN));
                        plugin.getLogger().warning(
                                "[VaultCaptureService] Vault capture aborted for " + actor.getName() +
                                        " at " + finalCaptureBlock.getWorld().getName() + ":" + finalCaptureBlock.getX() + "," + finalCaptureBlock.getY() + "," + finalCaptureBlock.getZ() +
                                        " - could not validate owner UUID"
                        );
                        busy = false;
                    }
                }.runTask(plugin);
                return;
            }

            service.storeThenRemove(finalCaptureBlock, validatedOwner, outcome.reProtectedHalves()).thenAccept(result -> {
                busy = false;
                switch (result) {
                    case CaptureResult.Vaulted vaulted -> {
                        actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_CAPTURED_OK));
                        service.mailOwner(finalCaptureBlock, actor.getUniqueId(), validatedOwner);
                        new PlayerVaultEvent(actor, vaulted.vault(), validatedOwner,
                                finalCaptureBlock.getLocation()).callEvent();
                    }
                    case CaptureResult.ChangedMeanwhile changed ->
                            actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_CHANGED_MEANWHILE));
                    case CaptureResult.Failed failed ->
                            actor.sendMessage(plugin.text().of(MessageKeys.CAPTURE_SAVE_FAILED));
                }
            });
        }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin));
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
        service.captureHanging(actor, (Hanging) clicked).whenComplete((vaulted, failure) -> busy = false);
    }
}
