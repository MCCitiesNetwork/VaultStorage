package com.minecraftcitiesnetwork.vaultstorage.database;

public sealed interface ReplaceResult {

    record Replaced() implements ReplaceResult {
    }

    record NoSuchVault() implements ReplaceResult {
    }

    /** The vault is there but this claim does not hold it. Nothing was written. */
    record NotHeld() implements ReplaceResult {
    }
}
