package com.minecraftcitiesnetwork.vaultstorage.database;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Vendor-neutral contract for the {@code Vault} table. The SQL lives on the vendor sub-interface. */
public interface VaultMapper {

    int insert(@NotNull VaultEntity vault);

    @Nullable VaultEntity selectByUuid(@NotNull UUID uuid);

    /** The owner, locking the row until the transaction ends; null when there is no such vault. */
    @Nullable UUID selectOwnerForUpdate(@NotNull UUID uuid);

    int updateOwner(@NotNull UUID uuid, @NotNull UUID ownerUuid, @NotNull Instant updatedTime);

    int touch(@NotNull UUID uuid, @NotNull Instant updatedTime);

    /** @return the number of rows deleted: 0 means there was no such vault */
    int delete(@NotNull UUID uuid);

    int count(@Nullable UUID ownerUuid, @Nullable UUID worldUuid);

    /** One page, oldest first, each vault with its item count. */
    @NotNull List<VaultSummary> selectPage(@Nullable UUID ownerUuid, @Nullable UUID worldUuid, int offset, int limit);

    /** @return 1 when the vault was free and is now claimed, 0 otherwise */
    int claim(@NotNull UUID uuid);

    int release(@NotNull UUID uuid);

    @NotNull List<UUID> selectClaimed();

    int releaseAll();

    /**
     * The owner's oldest unclaimed vault whose highest slot leaves room for {@code needed} more
     * below {@code maxSlots}, locked until the transaction ends; null when none has room.
     */
    @Nullable VaultRoom selectOldestWithRoom(@NotNull UUID ownerUuid, int needed, int maxSlots);
}
