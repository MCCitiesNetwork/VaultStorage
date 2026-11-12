package com.minecraftcitiesnetwork.vaultstorage.command;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Whether {@code /vault open} may show a vault to the sender. */
public sealed interface OpenOutcome {

    record Allowed() implements OpenOutcome {
    }

    record NoSuchVault() implements OpenOutcome {
    }

    /** The vault belongs to someone else and the sender may not open others' vaults. */
    record NotYours() implements OpenOutcome {
    }

    /** The database failed; the cause is in the log. */
    record Failed() implements OpenOutcome {
    }

    /**
     * @param owner       the vault's owner, or null when there is no such vault
     * @param mayOpenAny  whether the sender may open vaults that are not theirs
     */
    static @NotNull OpenOutcome decide(@Nullable UUID owner, @NotNull UUID sender,
                                       boolean mayOpenAny) {
        if (owner == null) {
            return new NoSuchVault();
        }
        return mayOpenAny || owner.equals(sender) ? new Allowed() : new NotYours();
    }
}
