package com.minecraftcitiesnetwork.vaultstorage.placement;

import java.util.UUID;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/**
 * One reason a player may place a vault at a spot: membership of a WorldGuard region there, a
 * GriefPrevention claim they may build in, and so on. Placement is allowed when any grant says
 * yes. Called on the main thread.
 */
@FunctionalInterface
public interface PlacementGrant {

    boolean mayPlace(@NotNull UUID player, @NotNull Location target);
}
