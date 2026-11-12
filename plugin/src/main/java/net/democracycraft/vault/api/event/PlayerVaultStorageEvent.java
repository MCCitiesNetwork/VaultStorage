package net.democracycraft.vault.api.event;

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
