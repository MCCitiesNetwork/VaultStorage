package com.minecraftcitiesnetwork.vaultstorage.placement;

import com.minecraftcitiesnetwork.vaultstorage.vault.PlayerVaultStorageEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** A player placed a vault back into the world. Fired after the block exists. */
public final class PlayerPlaceVaultEvent extends PlayerVaultStorageEvent {

    private final @NotNull Location location;

    public PlayerPlaceVaultEvent(@NotNull Player player, @NotNull Location location) {
        super(player);
        this.location = location;
    }

    /** Where the block was placed. */
    public @NotNull Location location() {
        return this.location.clone();
    }
}
