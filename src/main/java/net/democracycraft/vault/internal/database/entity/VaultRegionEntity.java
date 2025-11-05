package net.democracycraft.vault.internal.database.entity;

import java.util.UUID;

/**
 * Region dictionary entry to reference WorldGuard regions by stable id within a world.
 * We keep a synthetic UUID as PK to work with the current AutoTable (single-column PK),
 * and enforce a unique(worldUuid, regionId) later in the schema.
 */
public class VaultRegionEntity {
    public UUID uuid; // PK (synthetic row id)
    public UUID worldUuid; // world identifier owning the region
    public String regionId; // WorldGuard region id (per-world unique)

    public VaultRegionEntity() {}
}

