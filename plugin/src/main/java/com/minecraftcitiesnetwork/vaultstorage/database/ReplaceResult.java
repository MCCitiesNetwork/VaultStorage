package com.minecraftcitiesnetwork.vaultstorage.database;

public sealed interface ReplaceResult {

    record Replaced() implements ReplaceResult {
    }

    record NoSuchVault() implements ReplaceResult {
    }
}
