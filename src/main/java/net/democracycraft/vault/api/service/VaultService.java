package net.democracycraft.vault.api.service;

import net.democracycraft.vault.internal.database.entity.VaultEntity;
import net.democracycraft.vault.internal.database.entity.VaultItemEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public API: High-level service for managing Vault persistence.
 */
public interface VaultService extends Service {
    /**
     * Creates a vault record at the given world coordinates.
     * @return the persisted entity (never null)
     */
    @NotNull VaultEntity createVault(@NotNull UUID worldUuid, @NotNull UUID actor, int x, int y, int z, @NotNull UUID ownerUuid,
                              @Nullable String material, @Nullable String blockData);

    @NotNull Optional<VaultEntity> get(@NotNull UUID vaultUuid);
    @Nullable VaultEntity findByLocation(@NotNull UUID worldUuid, int x, int y, int z);
    void delete(@NotNull UUID vaultUuid);

    void setOwner(@NotNull UUID vaultUuid, @NotNull UUID ownerUuid);
    @Nullable UUID getOwner(@NotNull UUID vaultUuid);

    @NotNull List<VaultEntity> listByOwner(@NotNull UUID ownerUuid);
    @NotNull List<VaultEntity> listInWorld(@NotNull UUID worldUuid);

    /**
     * Inserts or updates a single item slot for a vault.
     */
    void putItem(@NotNull UUID vaultUuid, int slot, int amount, byte[] itemBytes);

    /**
     * Batch insert/update multiple item rows for the same vault.
     * Implementations should minimize database round-trips.
     */
    void putItems(@NotNull UUID vaultUuid, @NotNull List<VaultItemEntity> items);

    void removeItem(@NotNull UUID vaultUuid, int slot);
    @NotNull List<VaultItemEntity> listItems(@NotNull UUID vaultUuid);
}
