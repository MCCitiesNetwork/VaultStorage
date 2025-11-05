package net.democracycraft.vault.internal.database.entity;

import java.util.UUID;

/**
 * Many-to-one membership for a vault.
 * Synthetic UUID as PK; enforce UNIQUE(vaultUuid, memberUuid) in schema.
 */
public class VaultMemberEntity {
    public UUID uuid; // PK (synthetic row id)
    public UUID vaultUuid; // FK -> VaultEntity.uuid
    public UUID memberUuid; // player UUID

    public VaultMemberEntity() {}
}

