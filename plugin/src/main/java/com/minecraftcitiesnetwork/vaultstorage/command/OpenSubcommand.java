package com.minecraftcitiesnetwork.vaultstorage.command;

import com.minecraftcitiesnetwork.vaultstorage.database.FindResult;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.command.CommandContext;
import com.minecraftcitiesnetwork.vaultstorage.command.Subcommand;
import com.minecraftcitiesnetwork.vaultstorage.vault.VaultAction;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.minecraftcitiesnetwork.vaultstorage.permission.VaultPermission;
import org.jetbrains.annotations.NotNull;

/** /vault open <vaultId> [view|copy|edit] */
public class OpenSubcommand implements Subcommand {
    @Override public List<String> names() {
        return List.of("open", "inv");
    }
    @Override public @NotNull VaultPermission permission() {
        return VaultPermission.USER;
    }
    @Override public String usage() {
        return "open <vaultId> [view|copy|edit]";
    }

    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sender().sendMessage("Only players can use this.");
            return;
        }
        String idStr = ctx.require(0, "vaultId");
        UUID id;
        try {
            id = UUID.fromString(idStr);
        } catch (IllegalArgumentException ex) {
            ctx.sender().sendMessage("Invalid vault id.");
            return;
        }
        Player p = ctx.asPlayer();
        String mode = ctx.args().length >= 2 ? ctx.args()[1] : "view";
        VaultAction action;
        try {
            action = VaultAction.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            ctx.sender().sendMessage("Unknown action. Use view|copy|edit.");
            return;
        }
        if (!VaultPermission.has(p, VaultPermission.from(action))) {
            ctx.sender().sendMessage("You don't have permission for that action.");
            return;
        }

        var plugin = VaultStoragePlugin.getInstance();
        // Read on the main thread, before the lookup: permissions are not safe to read off it.
        boolean admin = VaultPermission.ADMIN.has(p);
        UUID opener = p.getUniqueId();
        CompletableFuture.supplyAsync(() -> plugin.repository().find(id), plugin.databaseExecutor())
                .thenAcceptAsync(found -> {
                    if (!(found instanceof FindResult.Found vault)) {
                        p.sendMessage("Vault not found.");
                        return;
                    }
                    if (!admin && !vault.vault().vault().ownerUuid().equals(opener)) {
                        p.sendMessage("You don't have access to that vault.");
                        return;
                    }
                    plugin.getInventoryService().openVirtualInventory(p, id, action);
                }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.SEVERE, "Could not open vault " + id + ".", failure);
                    p.sendMessage("The vault could not be opened.");
                    return null;
                });
    }

    @Override
    public List<String> complete(CommandContext ctx) {
        if (ctx.args().length == 2) {
            List<String> actions = new ArrayList<>();
            Player p = (ctx.sender() instanceof Player pl) ? pl : null;
            for (VaultAction a : VaultAction.values()) {
                if (p == null || VaultPermission.has(p, VaultPermission.from(a))) {
                    actions.add(a.name().toLowerCase(Locale.ROOT));
                }
            }
            return ctx.filter(actions, ctx.args()[1]);
        }
        // Avoid DB calls on main thread for id completion
        return List.of();
    }
}
