package com.minecraftcitiesnetwork.vaultstorage.command;

import com.minecraftcitiesnetwork.vaultstorage.database.OwnerResult;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.command.CommandContext;
import com.minecraftcitiesnetwork.vaultstorage.command.Subcommand;
import com.minecraftcitiesnetwork.vaultstorage.permission.VaultPermission;
import com.minecraftcitiesnetwork.vaultstorage.names.UniqueIdentifierResolver;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.logging.Level;
import java.util.UUID;

/**
 * {@code /vault transfer <vaultId> <newOwnerUUID|playerName>}
 * Admin-only command to transfer vault ownership.
 * Useful for recovering vaults that were created with incorrect UUIDs (e.g., Bedrock UUID conversion errors).
 */
public class TransferSubcommand implements Subcommand {

    @Override
    public List<String> names() {
        return List.of("transfer", "xfer");
    }

    @Override
    public @NotNull VaultPermission permission() {
        return VaultPermission.ADMIN;
    }

    @Override
    public String usage() {
        return "transfer <vaultId> <newOwnerUUID|playerName>";
    }

    @Override
    public void execute(CommandContext ctx) {
        // Parse vault ID
        String vaultIdStr = ctx.require(0, "vaultId");
        UUID vaultId;
        try {
            vaultId = UUID.fromString(vaultIdStr);
        } catch (IllegalArgumentException ex) {
            ctx.sender().sendMessage("Invalid vault ID format.");
            ctx.usage(usage());
            return;
        }

        // Parse owner identifier (UUID or player name)
        String ownerIdentifier = ctx.require(1, "newOwnerUUID or playerName");

        var plugin = VaultStoragePlugin.getInstance();
        var sender = ctx.sender();
        sender.sendMessage("Resolving owner...");

        new UniqueIdentifierResolver(plugin.names()).resolveUuid(ownerIdentifier)
                .thenCompose(newOwner -> {
                    if (!UniqueIdentifierResolver.isValidUUID(newOwner)) {
                        return CompletableFuture.completedFuture("Could not resolve player: " + ownerIdentifier);
                    }
                    return CompletableFuture.supplyAsync(() -> switch (plugin.repository().setOwner(vaultId, newOwner)) {
                        case OwnerResult.NoSuchVault missing -> "Vault not found with ID: " + vaultId;
                        case OwnerResult.Changed changed -> {
                            plugin.getLogger().info("[TransferSubcommand] " + sender.getName() + " transferred vault "
                                    + vaultId + " from " + changed.previous() + " to " + newOwner);
                            yield "Vault " + vaultId + " transferred from " + changed.previous() + " to " + newOwner + ".";
                        }
                    }, plugin.databaseExecutor());
                })
                .exceptionally(failure -> {
                    // The cause goes to the log; the sender is told only that it failed.
                    plugin.getLogger().log(Level.SEVERE, "Could not transfer vault " + vaultId + ".", failure);
                    return "The transfer failed. See the server log.";
                })
                .thenAcceptAsync(sender::sendMessage, plugin.getServer().getScheduler().getMainThreadExecutor(plugin));
    }

    @Override
    public List<String> complete(CommandContext ctx) {
        // Optionally suggest online player names for the second argument
        if (ctx.args().length == 2) {
            String prefix = ctx.args()[1];
            List<String> names = new java.util.ArrayList<>();
            for (var p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            return ctx.filter(names, prefix);
        }
        return List.of();
    }
}
