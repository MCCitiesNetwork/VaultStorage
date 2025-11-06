package net.democracycraft.vault.internal.command.impl;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.internal.command.framework.CommandContext;
import net.democracycraft.vault.internal.command.framework.Subcommand;
import net.democracycraft.vault.internal.data.VaultDtoImp;
import net.democracycraft.vault.internal.mappable.VaultImp;
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

/**
 * /vault capture: puts the player into capture mode to right-click a container and vault it.
 */
public class CaptureSubcommand implements Subcommand {
    @Override public List<String> names() { return List.of("capture", "cap"); }
    @Override public String permission() { return "vault.user"; }
    @Override public String usage() { return "capture"; }

    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.isPlayer()) { ctx.sender().sendMessage("Only players can use this."); return; }
        Player player = ctx.asPlayer();
        VaultSessionManager.Session session = VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(player.getUniqueId());
        session.getDynamicListener().setListener(new Listener() {
            @EventHandler
            public void onInteract(PlayerInteractEvent event) {
                if (!event.getPlayer().getUniqueId().equals(player.getUniqueId())) return;
                Action action = event.getAction();
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
                // Capture and persist
                VaultCaptureService svc = VaultStoragePlugin.getInstance().getCaptureService();
                VaultImp vault = svc.captureFromBlock(player, block);
                var plugin = VaultStoragePlugin.getInstance();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    var vs = plugin.getVaultService();
                    UUID worldId = block.getWorld().getUID();
                    UUID owner = player.getUniqueId();
                    // Avoid duplicates at same location
                    var existing = vs.findByLocation(worldId, block.getX(), block.getY(), block.getZ());
                    if (existing != null) {
                        vs.delete(existing.uuid);
                    }
                    UUID newId;
                    {
                        var created = vs.createVault(worldId, block.getX(), block.getY(), block.getZ(), owner,
                                vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                vault.blockDataString());
                        newId = created.uuid;
                    }
                    for (int i = 0; i < vault.contents().size(); i++) {
                        ItemStack it = vault.contents().get(i);
                        if (it == null) continue;
                        vs.putItem(newId, i, it.getAmount(), ItemSerialization.toBytes(it));
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        var dto = new VaultDtoImp(newId, owner, List.of(),
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
