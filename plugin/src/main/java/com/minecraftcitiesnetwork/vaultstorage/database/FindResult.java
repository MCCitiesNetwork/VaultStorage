package com.minecraftcitiesnetwork.vaultstorage.database;

import org.jetbrains.annotations.NotNull;

public sealed interface FindResult {

    record Found(@NotNull StoredVault vault) implements FindResult {
    }

    record NoSuchVault() implements FindResult {
    }
}
