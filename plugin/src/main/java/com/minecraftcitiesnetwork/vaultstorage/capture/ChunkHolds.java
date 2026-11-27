package com.minecraftcitiesnetwork.vaultstorage.capture;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Keeps chunks loaded while captures in them are between "stored" and "removed". A plugin chunk
 * ticket is one per chunk, not one per caller, so two captures in a chunk are counted here and
 * the ticket goes with the last of them. Main thread only.
 */
final class ChunkHolds {

    private record Key(@NotNull UUID world, long chunk) {
    }

    private final @NotNull Plugin plugin;
    private final Map<Key, Integer> holds = new HashMap<>();

    ChunkHolds(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /** Holds the chunk. Run what comes back, once and on the main thread, to let it go. */
    @NotNull Runnable hold(@NotNull Chunk chunk) {
        Key key = new Key(chunk.getWorld().getUID(), chunk.getChunkKey());
        if (this.holds.merge(key, 1, Integer::sum) == 1) {
            chunk.addPluginChunkTicket(this.plugin);
        }
        return () -> {
            if (this.holds.merge(key, -1, Integer::sum) <= 0) {
                this.holds.remove(key);
                chunk.removePluginChunkTicket(this.plugin);
            }
        };
    }
}
