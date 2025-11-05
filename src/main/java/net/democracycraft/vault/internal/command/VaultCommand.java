package net.democracycraft.vault.internal.command;

import net.democracycraft.vault.internal.ui.VaultCaptureMenu;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * /vault should only open the main menu. No capture/JSON logic lives here.
 */
public class VaultCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this.");
            return true;
        }
        if (args.length == 0 || (args.length >= 1 && args[0].equalsIgnoreCase("menu"))) {
            new VaultCaptureMenu(player).open();
            return true;
        }
        // Fallback: still open the menu and hint correct usage
        new VaultCaptureMenu(player).open();
        sender.sendMessage("Usage: /" + label + " [menu]");
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return List.of("menu");
        return Collections.emptyList();
    }
}
