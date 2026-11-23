package com.minecraftcitiesnetwork.vaultstorage.menu;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import java.util.UUID;

/**
 * Whose vaults a menu shows, carried from one screen to the next.
 *
 * @param actorUuid   the player the menu was opened for
 * @param filterOwner the one owner whose vaults are listed, or null for everyone's
 * @param admin       whether the menu was opened as the staff view
 */
public record VaultUIContext(@NotNull UUID actorUuid, @Nullable UUID filterOwner, boolean admin) {

    /**
     * Builds a self-filtered context (non-admin).
     */
    public static @NotNull VaultUIContext self(@NotNull UUID actorUuid) {
        return new VaultUIContext(actorUuid, actorUuid, false);

    }

    /**
     * Builds an admin, no-filter context.
     */
    public static @NotNull VaultUIContext admin(@NotNull UUID actorUuid) {
        return new VaultUIContext(actorUuid, null, true);
    }

    /**
     * Builds an admin context filtering by a specific owner UUID.
     */
    public static @NotNull VaultUIContext adminFiltered(@NotNull UUID actorUuid, @NotNull UUID ownerUuid) {
        return new VaultUIContext(actorUuid, ownerUuid, true);
    }
}

