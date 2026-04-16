package net.democracycraft.vault.internal.command.impl;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.service.VaultService;
import net.democracycraft.vault.internal.command.framework.CommandContext;
import net.democracycraft.vault.internal.command.framework.Subcommand;
import net.democracycraft.vault.internal.security.VaultPermission;
import net.democracycraft.vault.internal.util.uuid.UniqueIdentifierResolver;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * /vault transfer <vaultId> <newOwnerUUID|playerName>
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
        var vaultService = plugin.getVaultService();

        ctx.sender().sendMessage("Resolving vault and owner...");
        plugin.getLogger().info("[TransferSubcommand] Lookup initiated for vault: " + vaultId);

        // Async work: verify vault exists and resolve owner UUID
        new BukkitRunnable() {
            @Override
            public void run() {
                // Check if vault exists
                var vaultOpt = vaultService.get(vaultId);
                if (vaultOpt.isEmpty()) {
                    plugin.getLogger().warning("[TransferSubcommand] Vault not found for ID: " + vaultId + " (UUID format valid: " + vaultId.toString() + ")");
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        ctx.sender().sendMessage("✗ Vault not found with ID: " + vaultId);
                        ctx.sender().sendMessage("  Debug: UUID format verified. Check if vault exists in database.");
                    });
                    return;
                }

                var vault = vaultOpt.get();
                plugin.getLogger().info("[TransferSubcommand] Vault found: " + vaultId + " at world " + vault.worldUuid + " coords(" + vault.x + "," + vault.y + "," + vault.z + ")");

                // Resolve owner UUID using the centralized resolver
                UniqueIdentifierResolver resolver = new UniqueIdentifierResolver(plugin.getMojangService(), plugin.getBedrockUniqueIdentifierRetriever());
                resolver.resolveUuid(ownerIdentifier).thenAccept(resolvedUUID -> {
                    if (resolvedUUID == null) {
                        plugin.getLogger().warning("[TransferSubcommand] Failed to resolve owner identifier: " + ownerIdentifier);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            ctx.sender().sendMessage("✗ Could not resolve player: " + ownerIdentifier);
                        });
                    } else {
                        plugin.getLogger().info("[TransferSubcommand] Resolved owner " + ownerIdentifier + " to UUID: " + resolvedUUID);
                        performTransfer(ctx, plugin, vaultService, vaultId, resolvedUUID);
                    }
                });
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Performs the actual vault ownership transfer.
     */
    private void performTransfer(
            CommandContext ctx,
            VaultStoragePlugin plugin,
            VaultService vaultService,
            UUID vaultId,
            UUID newOwnerUUID) {

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    UUID oldOwner = vaultService.getOwner(vaultId);
                    plugin.getLogger().info("[TransferSubcommand] Transferring vault " + vaultId + " from " + oldOwner + " to " + newOwnerUUID);
                    vaultService.setOwner(vaultId, newOwnerUUID);
                    plugin.getLogger().info("[TransferSubcommand] Transfer completed successfully");

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        String oldOwnerStr = oldOwner != null ? oldOwner.toString() : "unknown";
                        ctx.sender().sendMessage("✓ Vault " + vaultId + " ownership transferred:");
                        ctx.sender().sendMessage("  Old owner: " + oldOwnerStr);
                        ctx.sender().sendMessage("  New owner: " + newOwnerUUID);
                    });
                } catch (Exception ex) {
                    plugin.getLogger().warning("[TransferSubcommand] Transfer error for vault " + vaultId + " to " + newOwnerUUID + ": " + ex.getMessage());
                    ex.printStackTrace();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        ctx.sender().sendMessage("✗ Transfer failed: " + ex.getMessage());
                    });
                }
            }
        }.runTaskAsynchronously(plugin);
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
