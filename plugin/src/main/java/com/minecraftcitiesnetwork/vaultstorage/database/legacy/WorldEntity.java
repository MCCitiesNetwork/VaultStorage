package com.minecraftcitiesnetwork.vaultstorage.database.legacy;

import java.util.UUID;

/**
 * World dictionary (optional, for name lookup). UUID is the authoritative key.
 */
public class WorldEntity {
    public UUID uuid; // PK (world UUID)
    public String name; // optional human-friendly name

    public WorldEntity() {}
}

