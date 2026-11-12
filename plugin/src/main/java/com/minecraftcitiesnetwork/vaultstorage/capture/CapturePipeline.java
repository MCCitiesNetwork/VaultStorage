package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.database.NewVault;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultStore;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Moves a container's items into a vault without ever having them in neither place.
 *
 * <ol>
 *   <li>Main thread: read the container. Change nothing.</li>
 *   <li>Database: store the vault and its items in one transaction.</li>
 *   <li>Main thread: if the container still holds what was read, empty and remove it.</li>
 *   <li>Database: if it changed instead, delete the vault that was just stored.</li>
 * </ol>
 *
 * <p>A failure at any step leaves the items somewhere. The price is that a crash between steps
 * 2 and 3 leaves them in both; that was chosen over losing them to a database error.
 */
public final class CapturePipeline<T> {

    private final @NotNull VaultStore store;
    private final @NotNull ContainerAccess<T> access;
    private final @NotNull Executor database;
    private final @NotNull Executor main;
    private final @NotNull Logger logger;

    public CapturePipeline(@NotNull VaultStore store, @NotNull ContainerAccess<T> access,
                           @NotNull Executor database, @NotNull Executor main, @NotNull Logger logger) {
        this.store = store;
        this.access = access;
        this.database = database;
        this.main = main;
        this.logger = logger;
    }

    /**
     * Captures the target for the given owner. Call on the main thread. The future completes on
     * the main thread and never exceptionally.
     */
    public @NotNull CompletableFuture<CaptureResult> capture(@NotNull T target, @NotNull UUID owner) {
        ContainerSnapshot snapshot;
        try {
            snapshot = this.access.snapshot(target);
        } catch (RuntimeException ex) {
            this.logger.log(Level.SEVERE, "Could not read a container for capture.", ex);
            return CompletableFuture.completedFuture(new CaptureResult.Failed(ex));
        }
        NewVault vault = new NewVault(snapshot.worldUuid(), snapshot.worldName(), snapshot.x(), snapshot.y(),
                snapshot.z(), owner, snapshot.material(), snapshot.blockData());
        return CompletableFuture
                .supplyAsync(() -> this.store.create(vault, snapshot.items()), this.database)
                .thenComposeAsync(created -> finish(target, snapshot, created), this.main)
                .exceptionally(failure -> {
                    Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                            ? failure.getCause() : failure;
                    this.logger.log(Level.SEVERE, "Capture failed at " + describe(snapshot)
                            + "; the container was left in place.", cause);
                    return new CaptureResult.Failed(cause);
                })
                // Whatever thread the last step ran on, the caller hears on the main thread.
                .thenApplyAsync(result -> result, this.main);
    }

    private @NotNull CompletableFuture<CaptureResult> finish(@NotNull T target, @NotNull ContainerSnapshot snapshot,
                                                             @NotNull UUID created) {
        if (this.access.stillMatches(target, snapshot)) {
            this.access.remove(target);
            return CompletableFuture.completedFuture(new CaptureResult.Vaulted(created));
        }
        // The container changed while it was being saved: the stored copy is stale, so it goes.
        return CompletableFuture.supplyAsync(() -> {
            try {
                this.store.delete(created);
                return new CaptureResult.ChangedMeanwhile();
            } catch (RuntimeException ex) {
                this.logger.log(Level.SEVERE, "Vault " + created + " holds a stale copy of the container at "
                        + describe(snapshot) + " and could not be deleted. Delete it by hand.", ex);
                return new CaptureResult.Failed(ex);
            }
        }, this.database);
    }

    private static @NotNull String describe(@NotNull ContainerSnapshot snapshot) {
        return snapshot.worldName() + " " + snapshot.x() + "," + snapshot.y() + "," + snapshot.z();
    }
}
