package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Gives a claimed vault back, and keeps trying when the database cannot be reached. A claim
 * that is never released leaves its vault "in use" until the next restart, and a release is
 * most likely to fail exactly when something else has just failed: during an outage.
 */
public final class ClaimReleases {

    private final @NotNull Consumer<Claim> release;
    private final @NotNull Consumer<Runnable> later;
    private final @NotNull Logger logger;

    /**
     * @param release releases one claim; blocks, and throws when the database cannot be reached
     * @param later   runs a task on the database executor after a pause; throws once the plugin
     *                is stopping
     */
    public ClaimReleases(@NotNull Consumer<Claim> release, @NotNull Consumer<Runnable> later, @NotNull Logger logger) {
        this.release = release;
        this.later = later;
        this.logger = logger;
    }

    /** Releases the claim now, or as soon as the database is back. Blocks; never throws. */
    public void release(@NotNull Claim claim) {
        attempt(claim, true);
    }

    private void attempt(@NotNull Claim claim, boolean first) {
        UUID vault = claim.vault();
        try {
            this.release.accept(claim);
            if (!first) {
                this.logger.info("Vault " + vault + " has been released.");
            }
        } catch (RuntimeException ex) {
            if (first) {
                this.logger.log(Level.WARNING, "Vault " + vault + " could not be released and stays in use"
                        + " until it can be. Trying again until the database answers.", ex);
            }
            try {
                this.later.accept(() -> attempt(claim, false));
            } catch (RuntimeException shuttingDown) {
                // The plugin is stopping. Startup releases every claim.
                this.logger.log(Level.WARNING, "Vault " + vault + " is still claimed at shutdown;"
                        + " it is released at the next start.");
            }
        }
    }
}
