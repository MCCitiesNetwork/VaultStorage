package com.minecraftcitiesnetwork.vaultstorage.vault;

import com.minecraftcitiesnetwork.vaultstorage.database.legacy.VaultEntity;
import org.bukkit.entity.Player;

public class PlayerDeleteVaultEvent extends PlayerVaultStorageEvent {
    private final VaultEntity vault;

    public PlayerDeleteVaultEvent(Player player, VaultEntity vault) {
        super(player);
        this.vault = vault;
    }

    public VaultEntity getVault() {
        return vault;
    }
}
