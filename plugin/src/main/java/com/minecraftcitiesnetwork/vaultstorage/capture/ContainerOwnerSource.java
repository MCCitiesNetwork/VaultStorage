package com.minecraftcitiesnetwork.vaultstorage.capture;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;

/**
 * Says whose a container is when nobody has locked it. Another plugin may know: a shop plugin
 * knows whose shop a chest is. A module adds one of these for the plugin it knows; with none
 * added, an unlocked container has no owner.
 */
public interface ContainerOwnerSource {

    /**
     * The player the container belongs to, or empty when this source cannot name one player.
     * Called on the main thread. Already complete when there is nothing to look up. Never
     * completes exceptionally.
     */
    @NotNull CompletableFuture<Optional<UUID>> ownerOf(@NotNull Block container);

    /**
     * The containers in a chunk that this source says are a player's. Reads no world, so it
     * may be called for a chunk that is not loaded, from any thread. Never completes
     * exceptionally. An entry is a lead to follow, not the last word: {@link #ownerOf} decides
     * once the container itself can be looked at.
     */
    @NotNull CompletableFuture<List<MarkedContainer>> ownedIn(@NotNull String world, int chunkX, int chunkZ);
}
