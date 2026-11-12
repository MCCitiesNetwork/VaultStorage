package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/** A vault with its items, ordered by slot. */
public record StoredVault(@NotNull VaultEntity vault, @NotNull List<VaultItemEntity> items) {
}
