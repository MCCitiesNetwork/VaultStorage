package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/** One page of a vault list, and how many vaults the filter matches in all. */
public record VaultPage(@NotNull List<VaultSummary> vaults, int total) {
}
