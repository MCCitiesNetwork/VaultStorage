package net.democracycraft.vault.internal.command.impl;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.internal.command.framework.CommandContext;
import net.democracycraft.vault.internal.command.framework.Subcommand;
import net.democracycraft.vault.internal.service.VaultInventoryService;
import net.democracycraft.vault.internal.ui.VaultAction;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /vault open <vaultId> [view|copy|edit] */
public class OpenSubcommand implements Subcommand {
    @Override public List<String> names() { return List.of("open", "inv"); }
    @Override public String permission() { return "vault.user"; }
    @Override public String usage() { return "open <vaultId> [view|copy|edit]"; }

    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.isPlayer()) { ctx.sender().sendMessage("Only players can use this."); return; }
        String idStr = ctx.require(0, "vaultId");
        UUID id;
        try { id = UUID.fromString(idStr); } catch (IllegalArgumentException ex) { ctx.sender().sendMessage("Invalid vault id."); return; }
        Player p = ctx.asPlayer();
        String mode = ctx.args().length >= 2 ? ctx.args()[1] : "view";
        VaultAction action;
        try { action = VaultAction.valueOf(mode.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ex) { ctx.sender().sendMessage("Unknown action. Use view|copy|edit."); return; }
        if (!p.hasPermission(action.permission()) && !p.hasPermission("vault.admin")) { ctx.sender().sendMessage("You don't have permission for that action."); return; }

        var plugin = VaultStoragePlugin.getInstance();
        new BukkitRunnable() {
            @Override public void run() {
                var vs = plugin.getVaultService();
                var entityOpt = vs.get(id);
                if (entityOpt.isEmpty()) {
                    new BukkitRunnable() { @Override public void run() { ctx.sender().sendMessage("Vault not found."); } }.runTask(plugin);
                    return;
                }
                UUID owner = vs.getOwner(id);
                boolean allowed = owner != null && owner.equals(p.getUniqueId()) || ctx.sender().hasPermission("vault.admin");
                if (!allowed) {
                    new BukkitRunnable() { @Override public void run() { ctx.sender().sendMessage("You don't have access to that vault."); } }.runTask(plugin);
                    return;
                }
                new BukkitRunnable() { @Override public void run() {
                    VaultInventoryService invSvc = plugin.getInventoryService();
                    invSvc.openVirtualInventory(p, id, action);
                }}.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    @Override
    public List<String> complete(CommandContext ctx) {
        if (ctx.args().length == 2) {
            List<String> actions = new ArrayList<>();
            Player p = (ctx.sender() instanceof Player pl) ? pl : null;
            for (VaultAction a : VaultAction.values()) {
                if (p == null || p.hasPermission(a.permission()) || p.hasPermission("vault.admin")) {
                    actions.add(a.name().toLowerCase(Locale.ROOT));
                }
            }
            return ctx.filter(actions, ctx.args()[1]);
        }
        // Avoid DB calls on main thread for id completion
        return List.of();
    }
}
