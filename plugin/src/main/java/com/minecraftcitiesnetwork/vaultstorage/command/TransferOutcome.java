package com.minecraftcitiesnetwork.vaultstorage.command;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** How {@code /vault transfer} ended. */
public sealed interface TransferOutcome {

    record Transferred(@NotNull UUID vault, @NotNull UUID from, @NotNull UUID to) implements TransferOutcome {
    }

    record NoSuchVault(@NotNull UUID vault) implements TransferOutcome {
    }

    record UnknownPlayer(@NotNull String name) implements TransferOutcome {
    }

    /** The database failed; the cause is in the log. */
    record Failed() implements TransferOutcome {
    }
}
