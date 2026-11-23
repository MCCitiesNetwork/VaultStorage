package com.minecraftcitiesnetwork.vaultstorage.database;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Vendor-neutral contract for the {@code Vault} table. The SQL lives on the vendor sub-interface. */
public interface VaultMapper {

    /** @param token the claim the vault is created under, or null for a vault nobody holds */
    int insert(@NotNull VaultEntity vault, @Nullable UUID token);

    @Nullable VaultEntity selectByUuid(@NotNull UUID uuid);

    /** The owner, locking the row until the transaction ends; null when there is no such vault. */
    @Nullable UUID selectOwnerForUpdate(@NotNull UUID uuid);

    /** @return 1 when the owner was changed, 0 when the vault is gone or someone holds it */
    int updateOwner(@NotNull UUID uuid, @NotNull UUID ownerUuid, @NotNull Instant updatedTime);

    int touch(@NotNull UUID uuid, @NotNull Instant updatedTime);

    /** As {@link #touch}, for a vault held with this token only. */
    int touchHeld(@NotNull UUID uuid, @NotNull UUID token, @NotNull Instant updatedTime);

    /** Deletes a vault that holds nothing and that nobody holds. @return the number of rows deleted */
    int deleteFreeIfEmpty(@NotNull UUID uuid);

    /** Deletes a vault held with this token only. @return the number of rows deleted */
    int deleteHeld(@NotNull UUID uuid, @NotNull UUID token);

    int count(@Nullable UUID ownerUuid, @Nullable UUID worldUuid);

    /** One page, oldest first, each vault with its item count. */
    @NotNull List<VaultSummary> selectPage(@Nullable UUID ownerUuid, @Nullable UUID worldUuid, int offset, int limit);

    /** @return 1 when the vault was free and is now claimed, 0 otherwise */
    int claim(@NotNull UUID uuid, @NotNull UUID token);

    /** @return 1 when the vault was held with this token and is now free, 0 otherwise */
    int release(@NotNull UUID uuid, @NotNull UUID token);

    @NotNull List<UUID> selectClaimed();

    int releaseAll();

    /**
     * The owner's oldest unclaimed vault whose highest slot leaves room for {@code needed} more
     * below {@code maxSlots}, locked until the transaction ends; null when none has room.
     */
    @Nullable VaultRoom selectOldestWithRoom(@NotNull UUID ownerUuid, int needed, int maxSlots);

    /** How many vaults have no item rows at all. */
    int countWithoutItems();
}
