package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

public sealed interface OwnerResult {

    record Changed(@NotNull UUID previous) implements OwnerResult {
    }

    record NoSuchVault() implements OwnerResult {
    }
}
