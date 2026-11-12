package com.minecraftcitiesnetwork.vaultstorage.database;

import org.jetbrains.annotations.NotNull;

/** One serialized item stack and the slot it goes in. */
public record VaultItem(int slot, int amount, byte @NotNull [] item) {
}
