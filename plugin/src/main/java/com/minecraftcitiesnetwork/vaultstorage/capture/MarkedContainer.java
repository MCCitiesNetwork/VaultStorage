package com.minecraftcitiesnetwork.vaultstorage.capture;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * A container some plugin says is a player's, known by the block that marks it: an attachment
 * of the container, at this position.
 */
public record MarkedContainer(int x, int y, int z, @NotNull UUID owner) {

    private static final int CHUNK_SHIFT = 4;

    public int chunkX() {
        return this.x >> CHUNK_SHIFT;
    }

    public int chunkZ() {
        return this.z >> CHUNK_SHIFT;
    }
}
