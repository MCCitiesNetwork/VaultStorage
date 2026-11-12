package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.names.UniqueIdentifierResolver;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Settles who a new vault belongs to before anything is stored. */
public final class CaptureOwners {

    private final @NotNull VaultStoragePlugin plugin;

    public CaptureOwners(@NotNull VaultStoragePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Validates that a UUID is not null and not all-zeros.
     * If invalid, attempts to resolve using the player's name via UniqueIdentifierResolver.
     * Returns a CompletableFuture with the valid UUID or null if resolution fails.
     */
    public @NotNull CompletableFuture<UUID> validate(@Nullable UUID candidateUUID, @NotNull Player actor) {
        // If already valid, return immediately
        if (UniqueIdentifierResolver.isValidUUID(candidateUUID)) {
            plugin.getLogger().info(
                "Owner UUID is valid for " + actor.getName() + ": " + candidateUUID
            );
            return CompletableFuture.completedFuture(candidateUUID);
        }

        if (VaultOwners.choose(candidateUUID, actor.getUniqueId()) == VaultOwners.Choice.ABORT) {
            plugin.getLogger().warning("The lock owner's id (" + candidateUUID
                    + ") is not usable. Nothing was captured for " + actor.getName()
                    + "; the vault will not be given to whoever clicked.");
            return CompletableFuture.completedFuture(null);
        }

        // The actor's own id is unusable (a Bedrock conversion error): look it up by their name.
        var resolver = new UniqueIdentifierResolver(plugin.names());

        plugin.getLogger().warning(
            "INVALID owner UUID detected for actor " + actor.getName() +
            " (" + candidateUUID + "). Attempting to resolve by player name..."
        );

        return resolver.resolveUuid(actor.getName()).thenApply(resolvedUUID -> {
            if (UniqueIdentifierResolver.isValidUUID(resolvedUUID)) {
                plugin.getLogger().info(
                    " Successfully resolved UUID for " + actor.getName() +
                    ": " + resolvedUUID + " (Bedrock player fallback)"
                );
                return resolvedUUID;
            } else {
                plugin.getLogger().warning(
                    "failed to resolve valid UUID for " + actor.getName() +
                    ". Vault capture aborted. (Invalid fallback UUID: " + resolvedUUID + ")"
                );
                return null;
            }
        });
    }
}
