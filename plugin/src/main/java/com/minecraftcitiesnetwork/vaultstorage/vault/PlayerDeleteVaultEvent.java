package com.minecraftcitiesnetwork.vaultstorage.vault;

import com.minecraftcitiesnetwork.vaultstorage.database.VaultEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Fired on the main thread just before an admin deletes a vault. */
public class PlayerDeleteVaultEvent extends PlayerVaultStorageEvent {

    private final @NotNull VaultEntity vault;

    public PlayerDeleteVaultEvent(@NotNull Player player, @NotNull VaultEntity vault) {
        super(player);
        this.vault = vault;
    }

    public @NotNull VaultEntity getVault() {
        return this.vault;
    }
}
