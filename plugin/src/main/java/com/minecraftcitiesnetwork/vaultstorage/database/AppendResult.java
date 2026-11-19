package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Where appended items went. Kept by the caller so that the append can be undone. */
public sealed interface AppendResult {

    /** The vault that now holds the items. */
    @NotNull UUID vault();

    /**
     * The items went into an existing vault.
     *
     * @param firstSlot the slot of the first appended item
     * @param slots     how many slots the append spans
     */
    record Appended(@NotNull UUID vault, int firstSlot, int slots) implements AppendResult {
    }

    /** No vault had room, so a new one was created. */
    record Created(@NotNull UUID vault) implements AppendResult {
    }
}
