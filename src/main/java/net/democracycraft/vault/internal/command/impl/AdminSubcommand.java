package net.democracycraft.vault.internal.command.impl;

import net.democracycraft.vault.internal.command.framework.CommandContext;
import net.democracycraft.vault.internal.command.framework.Subcommand;
import net.democracycraft.vault.internal.security.VaultPermission;
import net.democracycraft.vault.internal.ui.VaultCaptureMenu;
import net.democracycraft.vault.internal.ui.VaultUIContext;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /vault admin: opens the capture UI dialog in admin mode showing all vaults (no owner filter).
 * <p>
 * This grants an unfiltered view using {@link VaultUIContext#admin(java.util.UUID)} so every owner is visible.
 * Requires {@link VaultPermission#ADMIN}.
 */
public class AdminSubcommand implements Subcommand {
    @Override public List<String> names() { return List.of("admin"); }
    @Override public @NotNull VaultPermission permission() { return VaultPermission.ADMIN; }
    @Override public String usage() { return "admin"; }

    @Override
    public void execute(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        if (!(sender instanceof Player player)) { sender.sendMessage("Only players can use this."); return; }
        new VaultCaptureMenu(player, VaultUIContext.admin(player.getUniqueId())).open();
    }
}

