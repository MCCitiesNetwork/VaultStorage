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
    void createVault(@NotNull VaultEntity vault, @NotNull UUID ownerUuid);
    @Nullable VaultEntity getVault(@NotNull UUID vaultUuid);
    @Nullable VaultEntity findByLocation(@NotNull UUID worldUuid, int x, int y, int z);
    void deleteVault(@NotNull UUID vaultUuid);

    // Ownership
    void setOwner(@NotNull UUID vaultUuid, @NotNull UUID ownerUuid);
    @Nullable UUID getOwner(@NotNull UUID vaultUuid);

    // Queries
    @NotNull List<VaultEntity> listByOwner(@NotNull UUID ownerUuid);
    @NotNull List<VaultEntity> listInWorld(@NotNull UUID worldUuid);

    // Items
    void putItem(@NotNull UUID vaultUuid, int slot, int amount, byte[] itemBytes);
    void removeItem(@NotNull UUID vaultUuid, int slot);
    @NotNull List<VaultItemEntity> listItems(@NotNull UUID vaultUuid);
}
