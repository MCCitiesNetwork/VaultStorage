package net.democracycraft.vault.api.dao;

import net.democracycraft.vault.internal.database.entity.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Public API: DAO contract for Vault persistence.
 */
public interface VaultDAO {
    // Vault lifecycle
    /**
     * Persists a new vault and its owner record.
     * @param vault vault entity (uuid must be pre-populated)
     * @param ownerUuid owner UUID
     */
    void createVault(@NotNull VaultEntity vault, @NotNull UUID ownerUuid);
    /**
     * Retrieves a vault by its UUID.
     * @param vaultUuid vault id
     * @return vault or null if absent
     */
    @Nullable VaultEntity getVault(@NotNull UUID vaultUuid);
    /**
     * Finds a vault by location coordinates in a world.
     */
    @Nullable VaultEntity findByLocation(@NotNull UUID worldUuid, int x, int y, int z);
    /**
     * Deletes a vault row (cascade deletes related rows via FK constraints).
     */
    void deleteVault(@NotNull UUID vaultUuid);

    // Ownership
    /**
     * Sets or updates a vault owner.
     */
    void setOwner(@NotNull UUID vaultUuid, @NotNull UUID ownerUuid);
    /**
     * Gets the owner UUID for the vault.
     */
    @Nullable UUID getOwner(@NotNull UUID vaultUuid);

    // Queries
    /**
     * Lists all vaults owned by a player.
     */
    @NotNull List<VaultEntity> listByOwner(@NotNull UUID ownerUuid);
    /**
     * Lists all vaults located in a given world.
     */
    @NotNull List<VaultEntity> listInWorld(@NotNull UUID worldUuid);

    // Items
    /**
     * Inserts or updates a single item slot for a vault.
     */
    void putItem(@NotNull UUID vaultUuid, int slot, int amount, byte[] itemBytes);
    /**
     * Batch insert/update multiple item slots for a vault (atomic best-effort).
     * Implementations should minimize round-trips.
     */
    void putItems(@NotNull UUID vaultUuid, @NotNull List<VaultItemEntity> items);
    /**
     * Removes an item slot.
     */
    void removeItem(@NotNull UUID vaultUuid, int slot);
    /**
     * Lists all items for a vault ordered by slot.
     */
    @NotNull List<VaultItemEntity> listItems(@NotNull UUID vaultUuid);
}
