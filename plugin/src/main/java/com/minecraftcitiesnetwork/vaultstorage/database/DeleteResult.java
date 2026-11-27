package com.minecraftcitiesnetwork.vaultstorage.database;

public sealed interface DeleteResult {

    record Deleted() implements DeleteResult {
    }

    record NoSuchVault() implements DeleteResult {
    }

    /** The vault is there but this claim does not hold it. Nothing was deleted. */
    record NotHeld() implements DeleteResult {
    }
}
