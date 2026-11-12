package com.minecraftcitiesnetwork.vaultstorage.vault;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Something a player did to a vault. */
public abstract class PlayerVaultStorageEvent extends VaultStorageEvent {

    private final @NotNull Player player;

    protected PlayerVaultStorageEvent(@NotNull Player player) {
        this.player = player;
    }

    public @NotNull Player player() {
        return this.player;
    }
}
