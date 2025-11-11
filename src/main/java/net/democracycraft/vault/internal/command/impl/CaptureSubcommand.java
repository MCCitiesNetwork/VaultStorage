package net.democracycraft.vault.internal.command.impl;

import net.democracycraft.vault.VaultStoragePlugin;
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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import net.democracycraft.vault.internal.database.entity.VaultItemEntity;
import net.democracycraft.vault.internal.ui.VaultCaptureMenu;

/**
 * Subcommand: /vault capture
 * <p>Extended behavior: any Bolt-protected block can be captured. Non-container blocks behave like empty containers:
 * protection removed, no vault persisted.</p>
 * <p>Authorization policy (overlapping regions + region presence):</p>
 * <ul>
 *   <li>Override permission: may capture even if the block is outside any region (still requires Bolt owner).</li>
 *   <li>Inside regions: same rules as policy (owner vs member vs non-involved).</li>
 *   <li>Outside regions without override: never vaultable.</li>
 *   <li>Unprotected (no Bolt owner): never vaultable.</li>
 * </ul>
 */
public class CaptureSubcommand implements Subcommand {
    @Override public List<String> names() { return List.of("capture", "cap"); }
    @Override public @NotNull VaultPermission permission() { return VaultPermission.ACTION_CAPTURE; }
    @Override public String usage() { return "capture"; }

    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.isPlayer()) { ctx.sender().sendMessage("Only players can use this."); return; }
        Player player = ctx.asPlayer();
        if (!VaultPermission.ACTION_CAPTURE.has(player)) {
            player.sendMessage("You don't have permission to capture blocks.");
            return;
        }

        VaultSessionManager.Session session = VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(player.getUniqueId());
        session.getDynamicListener().setListener(new Listener() {
            @EventHandler
            public void onInteract(PlayerInteractEvent event) {
                if (!event.getPlayer().getUniqueId().equals(player.getUniqueId())) return;
                Action action = event.getAction();
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

                // Evaluate policy (internally resolves Bolt owner)
                VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluate(player, block);
                UUID originalOwner = decision.containerOwner();
                if (!decision.allowed()) {
                    player.sendMessage("Not allowed by region/block rules.");
                    return;
                }

                VaultCaptureService captureService = VaultStoragePlugin.getInstance().getCaptureService();
                VaultCaptureService.CaptureOutcome outcome = captureService.captureWithDoubleChestSupport(player, block, originalOwner, decision.hasOverride());

                if (outcome.empty()) {
                    player.sendMessage(VaultCaptureMenu.emptyCaptureMessage());
                    return;
                }

                if (originalOwner == null && decision.hasOverride()) {
                    player.sendMessage("No Bolt owner found; you will be set as the vault owner.");
                }

                VaultImp vault = outcome.vault();
                var plugin = VaultStoragePlugin.getInstance();
                UUID finalOwner = outcome.finalOwner();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    var vaultService = plugin.getVaultService();
                    UUID worldId = block.getWorld().getUID();
                    UUID newId;
                    {
                        var created = vaultService.createVault(worldId, block.getX(), block.getY(), block.getZ(), finalOwner,
                                vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                vault.blockDataString());
                        newId = created.uuid;
                    }
                    List<VaultItemEntity> batch = new ArrayList<>(vault.contents().size());
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
                    if (!batch.isEmpty()) vaultService.putItems(newId, batch);
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
        player.sendMessage("Right-click a block to capture it. Left-click to cancel.");
    }
}
