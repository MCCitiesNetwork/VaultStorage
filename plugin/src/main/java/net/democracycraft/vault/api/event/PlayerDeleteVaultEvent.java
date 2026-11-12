package net.democracycraft.vault.api.event;

import net.democracycraft.vault.internal.database.entity.VaultEntity;
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
