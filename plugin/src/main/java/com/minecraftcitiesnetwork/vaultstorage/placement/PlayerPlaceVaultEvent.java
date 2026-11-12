package com.minecraftcitiesnetwork.vaultstorage.placement;

import com.minecraftcitiesnetwork.vaultstorage.vault.PlayerVaultStorageEvent;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public class PlayerPlaceVaultEvent extends PlayerVaultStorageEvent {

    private final Location placementLocation;

    public PlayerPlaceVaultEvent(Player player, Location placementLocation) {
        super(player);
        this.placementLocation = placementLocation;
    }

    public Location getPlacementLocation() {
        return placementLocation;
    }
}
