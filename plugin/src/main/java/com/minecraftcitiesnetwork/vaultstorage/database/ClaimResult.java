package com.minecraftcitiesnetwork.vaultstorage.database;

import org.jetbrains.annotations.NotNull;

public sealed interface ClaimResult {

    /** The caller now holds the vault and must release or delete it. */
    record Claimed(@NotNull StoredVault vault) implements ClaimResult {
    }

    record AlreadyClaimed() implements ClaimResult {
    }

    record NoSuchVault() implements ClaimResult {
    }
}
