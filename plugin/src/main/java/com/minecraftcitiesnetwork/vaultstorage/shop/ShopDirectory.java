package com.minecraftcitiesnetwork.vaultstorage.shop;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;

/** The shop plugin's registry of live shops. Supplied by a module; absent, there are no shops. */
public interface ShopDirectory {

    /**
     * The shops whose sign is in a chunk. Reads a database, off the calling thread. Never
     * completes exceptionally: a failed read is logged and answers with no shops.
     */
    @NotNull CompletableFuture<List<Shop>> shopsInChunk(@NotNull String world, int chunkX, int chunkZ);
}
