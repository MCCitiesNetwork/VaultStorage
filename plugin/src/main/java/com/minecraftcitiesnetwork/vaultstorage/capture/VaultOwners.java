package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.names.UniqueIdentifierResolver;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Decides what to do when the id a vault would be owned by is not usable. */
public final class VaultOwners {

    public enum Choice {
        /** The id is fine. */
        USE,
        /** The id is the capturing player's own and is broken: look it up again by their name. */
        RESOLVE_ACTOR,
        /** The id is someone else's and is broken: capture nothing. */
        ABORT
    }

    private VaultOwners() {
    }

    /**
     * @param candidate who the vault would belong to: the lock's owner, or the actor when the
     *                  block has no lock owner
     * @param actor     the capturing player's own id
     */
    public static @NotNull Choice choose(@Nullable UUID candidate, @NotNull UUID actor) {
        if (UniqueIdentifierResolver.isValidUUID(candidate)) {
            return Choice.USE;
        }
        // A broken id may only be replaced with the actor's when it was the actor's to begin
        // with. Replacing a lock owner's broken id would hand their items to whoever clicked.
        return actor.equals(candidate) ? Choice.RESOLVE_ACTOR : Choice.ABORT;
    }
}
