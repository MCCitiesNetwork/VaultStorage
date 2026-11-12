package com.minecraftcitiesnetwork.vaultstorage.vault;

/**
 * Serializable world location for DTOs.
 * World is a string (world name), and coordinates are block coordinates.
 */
public interface LocationDto extends Dto {

    String world();

    int x();

    int y();

    int z();
}

