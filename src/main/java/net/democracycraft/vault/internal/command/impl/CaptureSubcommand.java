package net.democracycraft.vault.internal.command.impl;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.internal.command.framework.CommandContext;
import net.democracycraft.vault.internal.command.framework.Subcommand;
import net.democracycraft.vault.internal.data.VaultDtoImp;
import net.democracycraft.vault.internal.mappable.VaultImp;
import net.democracycraft.vault.internal.security.VaultCapturePolicy;
import net.democracycraft.vault.internal.security.VaultPermission;
import net.democracycraft.vault.internal.service.VaultCaptureService;
import net.democracycraft.vault.internal.session.VaultSessionManager;
import net.democracycraft.vault.internal.util.item.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import net.democracycraft.vault.internal.database.entity.VaultItemEntity;
import net.democracycraft.vault.internal.ui.VaultCaptureMenu;

/**
 * Subcommand: /vault capture
 * <p>
 * Authorization policy with overlapping-region awareness:
 * - Players with override permission may always capture.
 * - Region owners at the target may capture containers whose Bolt owner exists and is NOT a member nor an owner of ANY overlapping region, and is not themselves.
 * - Region members (who are not owners) cannot capture anything inside overlapping regions.
 * - Players who are non-members/non-owners across ALL overlapping regions may capture only their own Bolt-owned containers at that location.
 */
public class CaptureSubcommand implements Subcommand {
    @Override public List<String> names() { return List.of("capture", "cap"); }
    @Override public @NotNull VaultPermission permission() { return VaultPermission.ACTION_CAPTURE; }
    @Override public String usage() { return "capture"; }

    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.isPlayer()) { ctx.sender().sendMessage("Only players can use this."); return; }
        Player player = ctx.asPlayer();
        // Permission is also enforced by the dispatcher via permission(), but we give a clear message early.
        if (!VaultPermission.ACTION_CAPTURE.has(player)) {
            player.sendMessage("You don't have permission to capture containers.");
            return;
        }

        VaultSessionManager.Session session = VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(player.getUniqueId());
        session.getDynamicListener().setListener(new Listener() {
            @EventHandler
            public void onInteract(PlayerInteractEvent event) {
                if (!event.getPlayer().getUniqueId().equals(player.getUniqueId())) return;
                Action action = event.getAction();
                // Always cancel to avoid unintended interactions
                event.setCancelled(true);
                if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                    session.getDynamicListener().stop();
                    player.sendMessage("Capture cancelled.");
                    return;
                }
                if (action != Action.RIGHT_CLICK_BLOCK) return;
                Block block = event.getClickedBlock();
                if (block == null) return;
                session.getDynamicListener().stop();
                if (!(block.getState() instanceof Container)) {
                    player.sendMessage("That block is not a container.");
                    return;
                }

                // Resolve Bolt container owner (if service is present) via policy decision
                // (Policy obtains Bolt owner internally.)
                VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluateWithLog(player, block, "CaptureCheck");
                UUID originalOwner = decision.containerOwner();

                if (!decision.allowed()) {
                    player.sendMessage("Not allowed by region/container rules.");
                    return;
                }

                // Inform if no Bolt owner (final owner will be the actor via override)
                if (originalOwner == null && decision.hasOverride()) {
                    player.sendMessage("No Bolt owner found; you will be set as the vault owner.");
                }
                // Remove Bolt protection prior to vaulting
                BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
                if (bolt != null) {
                    try { bolt.removeProtection(block); } catch (Throwable ignored) {}
                }

                // Capture and persist
                VaultCaptureService svc = VaultStoragePlugin.getInstance().getCaptureService();
                VaultImp vault = svc.captureFromBlock(player, block);
                // If empty, skip persistence and notify.
                if (vault.contents().isEmpty()) {
                    player.sendMessage(VaultCaptureMenu.emptyCaptureMessage());
                    return;
                }
                var plugin = VaultStoragePlugin.getInstance();
                UUID finalOwner = originalOwner != null ? originalOwner : player.getUniqueId();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    var vs = plugin.getVaultService();
                    UUID worldId = block.getWorld().getUID();
                    // Avoid duplicates at same location
                    var existing = vs.findByLocation(worldId, block.getX(), block.getY(), block.getZ());
                    if (existing != null) {
                        vs.delete(existing.uuid);
                    }
                    UUID newId;
                    {
                        var created = vs.createVault(worldId, block.getX(), block.getY(), block.getZ(), finalOwner,
                                vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                vault.blockDataString());
                        newId = created.uuid;
                    }
                    // Batch persist contents
                    List<VaultItemEntity> batch = new java.util.ArrayList<>(vault.contents().size());
                    for (int i = 0; i < vault.contents().size(); i++) {
                        ItemStack it = vault.contents().get(i);
                        if (it == null) continue;
                        VaultItemEntity ve = new VaultItemEntity();
                        ve.vaultUuid = newId;
                        ve.slot = i;
                        ve.amount = it.getAmount();
                        ve.item = ItemSerialization.toBytes(it);
                        batch.add(ve);
                    }
                    if (!batch.isEmpty()) vs.putItems(newId, batch);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        var dto = new VaultDtoImp(newId, finalOwner, List.of(),
                                vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                null, System.currentTimeMillis());
                        VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(player.getUniqueId()).setLastVaultDto(dto);
                        player.sendMessage("Vault captured.");
                    });
                });
            }
        });
        session.getDynamicListener().start();
        player.sendMessage("Right-click a container to capture it. Left-click to cancel.");
    }
}
