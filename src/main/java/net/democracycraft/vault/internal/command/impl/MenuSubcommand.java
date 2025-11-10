package net.democracycraft.vault.internal.command.impl;

import net.democracycraft.vault.internal.command.framework.CommandContext;
import net.democracycraft.vault.internal.command.framework.Subcommand;
import net.democracycraft.vault.internal.ui.VaultCaptureMenu;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import net.democracycraft.vault.internal.security.VaultPermission;
import org.jetbrains.annotations.NotNull;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.service.MojangService;
import net.democracycraft.vault.internal.ui.VaultUIContext;
import org.bukkit.Bukkit;
import java.util.ArrayList;
import java.util.UUID;

/** /vault menu: opens the capture UI dialog. Supports optional username filter. */
public class MenuSubcommand implements Subcommand {
    @Override public List<String> names() { return List.of("menu", "ui"); }
    @Override public @NotNull VaultPermission permission() { return VaultPermission.USER; }
    @Override public String usage() { return "menu [username]"; }

    @Override
    public void execute(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        if (!(sender instanceof Player player)) { sender.sendMessage("Only players can use this."); return; }

        // Optional username parameter -> open filtered by that owner's UUID (requires ADMIN if not self)
        if (ctx.args().length >= 1) {
            String username = ctx.args()[0];
            var plugin = VaultStoragePlugin.getInstance();
            player.sendMessage("Resolving player...");
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                MojangService ms = plugin.getMojangService();
                UUID target = null;
                boolean serviceAvailable = ms != null;
                if (serviceAvailable) {
                    try {
                        target = ms.getUUID(username);
                    } catch (Throwable ignored) {
                        // Swallow and handle as not found below
                    }
                }
                UUID resolved = target;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player current = Bukkit.getPlayer(player.getUniqueId());
                    if (current == null || !current.isOnline()) return;
                    if (!serviceAvailable) { current.sendMessage("Mojang service unavailable."); return; }
                    if (resolved == null) { current.sendMessage("Player not found."); return; }
                    if (resolved.equals(current.getUniqueId())) {
                        new VaultCaptureMenu(current).open();
                    } else {
                        if (!VaultPermission.ADMIN.has(current)) {
                            current.sendMessage("You don't have permission to open other players' vaults.");
                            return;
                        }
                        new VaultCaptureMenu(current, VaultUIContext.adminFiltered(current.getUniqueId(), resolved)).open();
                    }
                });
            });
            return;
        }

        // Default behavior: self-filtered context
        new VaultCaptureMenu(player).open();
    }

    @Override
    public List<String> complete(CommandContext ctx) {
        // Suggest online player names for the optional username parameter
        if (ctx.args().length == 1) {
            String prefix = ctx.args()[0];
            List<String> names = new ArrayList<>();
            for (var p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return ctx.filter(names, prefix);
        }
        return List.of();
    }
}
