package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Vendor-neutral contract for the {@code VaultItem} table. */
public interface VaultItemMapper {

    /** Inserts every item in one statement. The list must not be empty. */
    int insertAll(@NotNull List<VaultItemEntity> items);

    /** A vault's items, ordered by slot. */
    @NotNull List<VaultItemEntity> selectByVault(@NotNull UUID vaultUuid);

    int deleteByVault(@NotNull UUID vaultUuid);

    /**
     * Deletes the items in slots {@code firstSlot} to {@code lastSlot}, both included, unless
     * someone holds the vault.
     *
     * @return the number of rows deleted
     */
    int deleteFreeSlots(@NotNull UUID vaultUuid, int firstSlot, int lastSlot);
}
