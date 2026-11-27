package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** What is needed to create a vault. The world name keeps the {@code World} table readable. */
public record NewVault(@NotNull UUID worldUuid, @NotNull String worldName, int x, int y, int z, @NotNull UUID ownerUuid,
                       @NotNull String material, @Nullable String blockData) {
}
