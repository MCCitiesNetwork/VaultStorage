package com.minecraftcitiesnetwork.vaultstorage.database;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A row of {@code Vault}, without the token of whoever holds it: that is the holder's {@link Claim}. */
public record VaultEntity(@NotNull UUID uuid, @NotNull UUID ownerUuid, @NotNull UUID worldUuid, int x, int y, int z,
                          @NotNull String material, @Nullable String blockData,
                          @NotNull Instant createdTime, @NotNull Instant updatedTime) {
}
