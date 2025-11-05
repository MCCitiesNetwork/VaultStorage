package net.democracycraft.vault.internal.database.entity;

import java.util.UUID;

/**
 * Link table between Vault and Region (many-to-many across worlds).
 * Synthetic UUID as PK; enforce UNIQUE(vaultUuid, regionUuid) in schema.
 */
public class VaultRegionLinkEntity {
    public UUID uuid; // PK (synthetic row id)
    public UUID vaultUuid;  // FK -> VaultEntity.uuid
    public UUID regionUuid; // FK -> RegionEntity.uuid

    public VaultRegionLinkEntity() {}
}

