package net.democracycraft.vault.internal.command.impl;

import net.democracycraft.vault.internal.command.framework.CommandContext;
import net.democracycraft.vault.internal.command.framework.Subcommand;
import net.democracycraft.vault.internal.ui.VaultCaptureMenu;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import net.democracycraft.vault.internal.security.VaultPermission;
import org.jetbrains.annotations.NotNull;

/** /vault menu: opens the capture UI dialog. */
public class MenuSubcommand implements Subcommand {
    @Override public List<String> names() { return List.of("menu", "ui"); }
    @Override public @NotNull VaultPermission permission() { return VaultPermission.USER; }
    @Override public String usage() { return "menu"; }

    @Override
    public void execute(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        if (!(sender instanceof Player player)) { sender.sendMessage("Only players can use this."); return; }
        new VaultCaptureMenu(player).open();
    }
}
