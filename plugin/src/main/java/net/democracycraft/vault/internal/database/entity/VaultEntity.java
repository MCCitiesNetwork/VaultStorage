package net.democracycraft.vault.internal.database.entity;

import java.util.UUID;

/**
 * Vault metadata stored in the database (normalized, no lists).
 */
public class VaultEntity {
    public UUID uuid; // PK - vault identifier
    public UUID worldUuid; // world identifier
    public int x;
    public int y;
    public int z;
    /** Original block material name (e.g., CHEST). */
    public String material;
    /** Serialized BlockData string capturing block state/orientation. */
    public String blockData;
    public String status; // optional status label
    public String type;   // optional type label
    public Long createdAtEpochMillis;
    public Long updatedAtEpochMillis;

    public VaultEntity() {}
}
