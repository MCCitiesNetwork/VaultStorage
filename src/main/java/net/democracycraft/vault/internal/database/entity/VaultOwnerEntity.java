package net.democracycraft.vault.internal.database.entity;

import java.util.UUID;

/**
 * One-to-one ownership for a vault.
 * Primary key is the vaultUuid to enforce exactly one owner per vault.
 */
public class VaultOwnerEntity {
    public UUID vaultUuid; // PK and FK -> VaultEntity.uuid
    public UUID ownerUuid; // player UUID

    public VaultOwnerEntity() {}
}

