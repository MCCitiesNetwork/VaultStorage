package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** A row of {@code VaultItem}: one serialized item stack in one slot of a vault. */
public record VaultItemEntity(@NotNull UUID vaultUuid, int slot, int amount, byte @NotNull [] item) {
}
