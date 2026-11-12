package com.minecraftcitiesnetwork.vaultstorage.command;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.config.Text;
import com.minecraftcitiesnetwork.vaultstorage.command.CommandContext;
import com.minecraftcitiesnetwork.vaultstorage.command.Subcommand;
import com.minecraftcitiesnetwork.vaultstorage.permission.VaultPermission;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultCaptureMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultUIContext;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /vault admin: opens the capture UI dialog in admin mode showing all vaults (no owner filter).
 * <p>
 * This grants an unfiltered view using {@link VaultUIContext#admin(UUID)} so every owner is visible.
 * Requires {@link VaultPermission#ADMIN}.
 */
public class AdminSubcommand implements Subcommand {
    @Override public List<String> names() {
        return List.of("admin");
    }
    @Override public @NotNull VaultPermission permission() {
        return VaultPermission.ADMIN;
    }
    @Override public String usage() {
        return "admin";
    }

    @Override
    public void execute(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.of(MessageKeys.COMMAND_PLAYERS_ONLY));
            return;
        }
        new VaultCaptureMenu(player, VaultUIContext.admin(player.getUniqueId())).open();
    }
}

