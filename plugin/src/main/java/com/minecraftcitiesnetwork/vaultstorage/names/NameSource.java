package com.minecraftcitiesnetwork.vaultstorage.names;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;

/**
 * Where a player's name or UUID is looked up. Both methods may complete on any thread and never
 * complete exceptionally: a failed lookup is an empty result.
 */
public interface NameSource {

    /** The player's current name, or empty when nobody knows the UUID. */
    @NotNull CompletableFuture<Optional<String>> nameOf(@NotNull UUID player);

    /** The UUID of whoever has this name now, or empty. */
    @NotNull CompletableFuture<Optional<UUID>> uuidOf(@NotNull String name);
}
