package com.minecraftcitiesnetwork.vaultstorage.placement;

import com.minecraftcitiesnetwork.vaultstorage.region.WorldGuardService;
import java.util.UUID;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** A player may place where they own or are a member of a WorldGuard region. */
public final class WorldGuardPlacementGrant implements PlacementGrant {

    private final @NotNull WorldGuardService regions;

    public WorldGuardPlacementGrant(@NotNull WorldGuardService regions) {
        this.regions = regions;
    }

    @Override
    public boolean mayPlace(@NotNull UUID player, @NotNull Location target) {
        return this.regions.getRegionsAt(target.getBlock()).stream().anyMatch(region -> region.isPartOfRegion(player));
    }
}
