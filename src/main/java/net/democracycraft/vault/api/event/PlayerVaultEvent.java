package net.democracycraft.vault.api.event;

import net.democracycraft.vault.api.convertible.Vault;
import org.bukkit.entity.Player;

public class PlayerVaultEvent extends PlayerVaultStorageEvent{

    private final Vault vault;

    public PlayerVaultEvent(Player player, Vault vault) {
        super(player);
        this.vault = vault;
    }

    public Vault getVault() {
        return vault;
    }
}
