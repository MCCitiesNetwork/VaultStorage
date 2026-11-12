package net.democracycraft.vault.internal.database.entity;

import java.util.UUID;

/**
 * Content entry for a vault at a specific slot.
 * Synthetic UUID as PK; enforce UNIQUE(vaultUuid, slot) in schema.
 */
public class VaultItemEntity {
    public UUID uuid;      // PK (synthetic row id)
    public UUID vaultUuid; // FK -> VaultEntity.uuid
    public int slot;       // slot index in the vault
    public int amount;     // stack amount
    public byte[] item;    // serialized ItemStack (BLOB)

    public VaultItemEntity() {}
}

