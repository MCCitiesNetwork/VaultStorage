package com.minecraftcitiesnetwork.vaultstorage.chestshop;

/** Where a shop's sign is, within one world. */
public record ShopPosition(int x, int y, int z) {

    private static final int CHUNK_SHIFT = 4;

    public int chunkX() {
        return this.x >> CHUNK_SHIFT;
    }

    public int chunkZ() {
        return this.z >> CHUNK_SHIFT;
    }
}
