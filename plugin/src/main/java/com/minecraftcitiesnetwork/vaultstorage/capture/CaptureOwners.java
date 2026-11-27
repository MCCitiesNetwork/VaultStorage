package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.names.Names;
import java.util.function.Supplier;
import java.util.logging.Logger;
import com.minecraftcitiesnetwork.vaultstorage.names.UniqueIdentifierResolver;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Settles who a new vault belongs to before anything is stored. */
public final class CaptureOwners {

    private final @NotNull Logger logger;
    private final @NotNull Supplier<Names> names;

    public CaptureOwners(@NotNull Logger logger, @NotNull Supplier<Names> names) {
        this.logger = logger;
        this.names = names;
    }

    /**
     * Validates that a UUID is not null and not all-zeros.
     * If invalid, attempts to resolve using the player's name via UniqueIdentifierResolver.
     * Returns a CompletableFuture with the valid UUID or null if resolution fails.
     */
    public @NotNull CompletableFuture<UUID> validate(@Nullable UUID candidateUUID, @NotNull Player actor) {
        // If already valid, return immediately
        if (UniqueIdentifierResolver.isValidUUID(candidateUUID)) {
            return CompletableFuture.completedFuture(candidateUUID);
        }

        if (VaultOwners.choose(candidateUUID, actor.getUniqueId()) == VaultOwners.Choice.ABORT) {
            this.logger.warning("The lock owner's id (" + candidateUUID
                    + ") is not usable. Nothing was captured for " + actor.getName()
                    + "; the vault will not be given to whoever clicked.");
            return CompletableFuture.completedFuture(null);
        }

        // The actor's own id is unusable (a Bedrock conversion error): look it up by their name.
        var resolver = new UniqueIdentifierResolver(this.names.get());

        this.logger.warning(
            "INVALID owner UUID detected for actor " + actor.getName() +
            " (" + candidateUUID + "). Attempting to resolve by player name..."
        );

        return resolver.resolveUuid(actor.getName()).thenApply(resolvedUUID -> {
            if (UniqueIdentifierResolver.isValidUUID(resolvedUUID)) {
                this.logger.info(
                    " Successfully resolved UUID for " + actor.getName() +
                    ": " + resolvedUUID + " (Bedrock player fallback)"
                );
                return resolvedUUID;
            } else {
                this.logger.warning(
                    "failed to resolve valid UUID for " + actor.getName() +
                    ". Vault capture aborted. (Invalid fallback UUID: " + resolvedUUID + ")"
                );
                return null;
            }
        });
    }
}
