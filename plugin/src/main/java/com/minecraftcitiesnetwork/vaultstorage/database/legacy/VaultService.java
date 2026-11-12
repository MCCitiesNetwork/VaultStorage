package com.minecraftcitiesnetwork.vaultstorage.database.legacy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The persistence calls the capture, placement, menu and command code still makes. It is an
 * adapter over {@code VaultRepository} that exists only until those callers are rewritten to
 * use the repository directly. Every method blocks on the database.
 */
public interface VaultService {

    /**
     * Mails a vault's owner that it was created at the given block. Reads Essentials' user
     * files, so it is called off the main thread.
     */
    void mailOwner(@NotNull UUID worldUuid, @NotNull UUID actor, int x, int y, int z, @NotNull UUID ownerUuid);

    /** Creates an empty vault. Items are added with {@link #putItems}. */
    @NotNull VaultEntity createVault(@NotNull UUID worldUuid, @NotNull UUID actor, int x, int y, int z,
                                     @NotNull UUID ownerUuid, @Nullable String material, @Nullable String blockData);

    @NotNull Optional<VaultEntity> get(@NotNull UUID vaultUuid);

    void delete(@NotNull UUID vaultUuid);

    void setOwner(@NotNull UUID vaultUuid, @NotNull UUID ownerUuid);

    @Nullable UUID getOwner(@NotNull UUID vaultUuid);

    @NotNull List<VaultEntity> listByOwner(@NotNull UUID ownerUuid);

    @NotNull List<VaultEntity> listInWorld(@NotNull UUID worldUuid);

    @NotNull List<VaultEntity> listByOwnerUuidString(@NotNull String ownerUuidString);

    @NotNull List<VaultEntity> listByVaultUuidString(@NotNull String vaultUuidString);

    /** Adds items at the slots they name. */
    void putItems(@NotNull UUID vaultUuid, @NotNull List<VaultItemEntity> items);

    /** Swaps a vault's whole contents in one transaction. */
    void replaceItems(@NotNull UUID vaultUuid, @NotNull List<VaultItemEntity> items);

    @NotNull List<VaultItemEntity> listItems(@NotNull UUID vaultUuid);
}
