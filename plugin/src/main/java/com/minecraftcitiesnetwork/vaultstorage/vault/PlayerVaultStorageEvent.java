package com.minecraftcitiesnetwork.vaultstorage.vault;

import org.bukkit.entity.Player;

public class PlayerVaultStorageEvent extends VaultStorageEvent {
    private final Player player;

    public PlayerVaultStorageEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }
}
