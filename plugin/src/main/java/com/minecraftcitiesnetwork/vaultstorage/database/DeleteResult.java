package com.minecraftcitiesnetwork.vaultstorage.database;

public sealed interface DeleteResult {

    record Deleted() implements DeleteResult {
    }

    record NoSuchVault() implements DeleteResult {
    }
}
