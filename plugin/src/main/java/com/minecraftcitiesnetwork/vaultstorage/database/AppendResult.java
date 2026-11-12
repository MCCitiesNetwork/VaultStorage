package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

public sealed interface AppendResult {

    /** The items went into an existing vault. */
    record Appended(@NotNull UUID vault) implements AppendResult {
    }

    /** No vault had room, so a new one was created. */
    record Created(@NotNull UUID vault) implements AppendResult {
    }
}
