package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.vault.PlayerVaultStorageEvent;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** A player vaulted a container or a hanging entity. Fired after the vault is stored. */
public final class PlayerVaultEvent extends PlayerVaultStorageEvent {

    private final @NotNull UUID vault;
    private final @NotNull UUID owner;
    private final @NotNull Location location;

    public PlayerVaultEvent(@NotNull Player player, @NotNull UUID vault, @NotNull UUID owner,
                            @NotNull Location location) {
        super(player);
        this.vault = vault;
        this.owner = owner;
        this.location = location;
    }

    /** The vault's id. */
    public @NotNull UUID vault() {
        return this.vault;
    }

    /** Who the vault belongs to; not always the player who captured it. */
    public @NotNull UUID owner() {
        return this.owner;
    }

    /** Where the container stood. */
    public @NotNull Location location() {
        return this.location.clone();
    }
}
