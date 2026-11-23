package com.minecraftcitiesnetwork.vaultstorage.placement;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.database.ClaimResult;
import com.minecraftcitiesnetwork.vaultstorage.database.StoredVault;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Places a vault back into the world.
 *
 * <ol>
 *   <li>Database: claim the vault. Of two actors, exactly one gets it.</li>
 *   <li>Main thread: place the block, fill it, protect it.</li>
 *   <li>Database: delete the vault if the block went in, or release the claim if it did not.</li>
 * </ol>
 *
 * <p>The vault is never deleted before its block exists, and a claimed vault cannot be placed a
 * second time, so a failure at any step leaves the items in at least one place and never two.
 */
public final class VaultPlacementService {

    private final @NotNull PlacementStore store;
    private final @NotNull PlacementWorld world;
    private final @NotNull Executor database;
    private final @NotNull Executor mainThread;
    private final @NotNull Logger logger;

    public VaultPlacementService(@NotNull PlacementStore store, @NotNull PlacementWorld world,
                                 @NotNull Executor database, @NotNull Executor mainThread, @NotNull Logger logger) {
        this.store = store;
        this.world = world;
        this.database = database;
        this.mainThread = mainThread;
        this.logger = logger;
    }

    /** {@code messageKey} names the line in messages.yml that tells the player what happened. */
    public record Result(boolean success, @NotNull String messageKey) {}

    /**
     * Places the vault at the target. The callback runs on the main thread.
     *
     * @param vaultUuid vault id
     * @param targetLoc location to place (must be in a loaded world)
     * @param callback result consumer (main thread), may be null
     */
    public void placeFromDatabaseRelativeAsync(@NotNull UUID vaultUuid, @NotNull Location targetLoc,
                                               @Nullable Consumer<Result> callback) {
        Consumer<Result> reply = callback == null ? result -> { } : callback;

        CompletableFuture.supplyAsync(() -> this.store.claim(vaultUuid), this.database)
                .thenComposeAsync(claim -> switch (claim) {
                    case ClaimResult.NoSuchVault missing ->
                            CompletableFuture.completedFuture(new Result(false, MessageKeys.INVENTORY_NOT_FOUND));
                    case ClaimResult.AlreadyClaimed held ->
                            CompletableFuture.completedFuture(new Result(false, MessageKeys.INVENTORY_IN_USE));
                    case ClaimResult.Claimed claimed -> placeClaimed(claimed.vault(), targetLoc);
                }, this.mainThread)
                .exceptionally(failure -> {
                    this.logger.log(Level.SEVERE, "Placing vault " + vaultUuid + " failed.", failure);
                    return new Result(false, MessageKeys.PLACEMENT_FAILED);
                })
                .thenAcceptAsync(reply, this.mainThread);
    }

    /** Main thread. The vault is claimed; it is deleted or released before this future completes. */
    private @NotNull CompletableFuture<Result> placeClaimed(@NotNull StoredVault stored, @NotNull Location targetLoc) {
        UUID vaultUuid = stored.vault().uuid();
        Result placed;
        try {
            placed = this.world.place(stored, targetLoc);
        } catch (RuntimeException ex) {
            this.logger.log(Level.SEVERE, "Placing vault " + vaultUuid + " failed; it stays in storage.", ex);
            placed = new Result(false, MessageKeys.PLACEMENT_FAILED);
        }
        if (!placed.success()) {
            Result refused = placed;
            return CompletableFuture.supplyAsync(() -> {
                this.store.release(vaultUuid);
                return refused;
            }, this.database);
        }
        try {
            this.world.protect(targetLoc, stored.vault().ownerUuid());
        } catch (RuntimeException ex) {
            this.logger.log(Level.WARNING, "Vault " + vaultUuid
                    + " was placed but could not be locked for its owner.", ex);
        }
        Result done = placed;
        return CompletableFuture.supplyAsync(() -> {
            try {
                this.store.delete(vaultUuid);
            } catch (RuntimeException ex) {
                // The block is in the world. The vault stays claimed, so it cannot be placed again.
                this.logger.log(Level.SEVERE, "Vault " + vaultUuid + " was placed at " + describe(targetLoc)
                        + " but could not be deleted. Delete it by hand.", ex);
            }
            return done;
        }, this.database);
    }

    private static @NotNull String describe(@NotNull Location location) {
        String world = location.getWorld() == null ? "an unloaded world" : location.getWorld().getName();
        return world + " " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }
}
