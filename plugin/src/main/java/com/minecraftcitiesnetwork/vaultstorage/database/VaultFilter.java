package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Which vaults a list or a count covers. A null component does not filter. */
public record VaultFilter(@Nullable UUID ownerUuid, @Nullable UUID worldUuid) {
}
