package com.minecraftcitiesnetwork.vaultstorage.database;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A row of {@code Vault}. {@code status} is null for a stored vault and CLAIMED while one actor holds it. */
public record VaultEntity(@NotNull UUID uuid, @NotNull UUID ownerUuid, @NotNull UUID worldUuid, int x, int y, int z,
                          @NotNull String material, @Nullable String blockData, @Nullable String status,
                          @NotNull Instant createdTime, @NotNull Instant updatedTime) {
}
