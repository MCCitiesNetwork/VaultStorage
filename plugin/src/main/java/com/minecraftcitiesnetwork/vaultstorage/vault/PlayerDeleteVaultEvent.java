package com.minecraftcitiesnetwork.vaultstorage.vault;

import com.minecraftcitiesnetwork.vaultstorage.database.VaultEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** A player is deleting a vault. Fired before the delete, while the vault still exists. */
public final class PlayerDeleteVaultEvent extends PlayerVaultStorageEvent {

    private final @NotNull VaultEntity vault;

    public PlayerDeleteVaultEvent(@NotNull Player player, @NotNull VaultEntity vault) {
        super(player);
        this.vault = vault;
    }

    public @NotNull VaultEntity vault() {
        return this.vault;
    }
}
