package com.minecraftcitiesnetwork.vaultstorage.database;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** What a list shows for one vault, without loading its items. */
public record VaultSummary(@NotNull UUID uuid, @NotNull UUID ownerUuid, @NotNull UUID worldUuid, int x, int y, int z,
                           @NotNull String material, @NotNull Instant createdTime, int itemCount) {
}
