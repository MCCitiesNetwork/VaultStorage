package com.minecraftcitiesnetwork.vaultstorage.capture;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Moves what a container or a hanging entity holds into a vault without ever having it in
 * neither place. Every capture goes through here.
 *
 * <ol>
 *   <li>Main thread: read the target. Change nothing.</li>
 *   <li>Database: store its items in one transaction.</li>
 *   <li>Main thread: if the target still holds what was read, remove it, and let the store
 *       settle.</li>
 *   <li>Database: if it changed instead, take back what was just stored.</li>
 * </ol>
 *
 * <p>A failure at any step leaves the items somewhere. The price is that a crash between steps
 * 2 and 3 leaves them in both; that was chosen over losing them to a database error.
 */
public final class CapturePipeline<T> {

    private final @NotNull CaptureStore store;
    private final @NotNull ContainerAccess<T> access;
    private final @NotNull Executor database;
    private final @NotNull Executor main;
    private final @NotNull Logger logger;

    public CapturePipeline(@NotNull CaptureStore store, @NotNull ContainerAccess<T> access,
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
            this.logger.log(Level.SEVERE, "Could not read what was to be captured.", ex);
            return CompletableFuture.completedFuture(new CaptureResult.Failed(ex));
        }
        return CompletableFuture
                .supplyAsync(() -> this.store.store(snapshot, owner), this.database)
                .thenComposeAsync(stored -> finish(target, snapshot, stored), this.main)
                .exceptionally(failure -> {
                    Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                            ? failure.getCause() : failure;
                    this.logger.log(Level.SEVERE, "Capture failed at " + describe(snapshot)
                            + "; nothing was removed from the world.", cause);
                    return new CaptureResult.Failed(cause);
                })
                // Whatever thread the last step ran on, the caller hears on the main thread.
                .thenApplyAsync(result -> result, this.main);
    }

    private @NotNull CompletableFuture<CaptureResult> finish(@NotNull T target, @NotNull ContainerSnapshot snapshot,
                                                             CaptureStore.@NotNull Stored stored) {
        if (this.access.stillMatches(target, snapshot)) {
            Runnable settle = stored.settle();
            try {
                this.access.remove(target);
            } catch (RuntimeException ex) {
                // The items are stored and the capture is over, so the store lets go either way.
                if (settle != null) {
                    this.database.execute(settle);
                }
                throw ex;
            }
            CaptureResult vaulted = new CaptureResult.Vaulted(stored.vault());
            // The caller hears "vaulted" once the vault is its owner's to open.
            return settle == null ? CompletableFuture.completedFuture(vaulted)
                    : CompletableFuture.runAsync(settle, this.database).thenApply(settled -> vaulted);
        }
        // The target changed while it was being saved: the stored copy is stale, so it goes.
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (stored.undo().getAsBoolean()) {
                    return new CaptureResult.ChangedMeanwhile();
                }
                this.logger.log(Level.SEVERE, "Vault " + stored.vault() + " holds a stale copy of what is at "
                        + describe(snapshot) + ". It is in use, so the copy was not taken back. Remove it by hand.");
                return new CaptureResult.Failed(new IllegalStateException("vault " + stored.vault() + " is in use"));
            } catch (RuntimeException ex) {
                this.logger.log(Level.SEVERE, "Vault " + stored.vault() + " holds a stale copy of what is at "
                        + describe(snapshot) + ", which could not be taken back. Remove it by hand.", ex);
                return new CaptureResult.Failed(ex);
            }
        }, this.database);
    }

    private static @NotNull String describe(@NotNull ContainerSnapshot snapshot) {
        return snapshot.worldName() + " " + snapshot.x() + "," + snapshot.y() + "," + snapshot.z();
    }
}
