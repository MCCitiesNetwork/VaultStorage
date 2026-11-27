package com.minecraftcitiesnetwork.vaultstorage.griefprevention;

import com.minecraftcitiesnetwork.vaultstorage.placement.PlacementGrant;
import java.util.UUID;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/**
 * A player may place a vault inside a GriefPrevention claim where GriefPrevention would let them
 * place a block: the owner, anyone with build or manage trust, and everyone when the claim
 * trusts the public to build. The rule is GriefPrevention's own check, not a copy of it.
 *
 * <p>The only class that names GriefPrevention's types.
 */
public final class GriefPreventionPlacementGrant implements PlacementGrant {

    private GriefPreventionPlacementGrant() {
    }

    public static @NotNull PlacementGrant create() {
        return new GriefPreventionPlacementGrant();
    }

    @Override
    public boolean mayPlace(@NotNull UUID player, @NotNull Location target) {
        // false: respect claim height. null: no cached claim to try first.
        Claim claim = GriefPrevention.instance.dataStore.getClaimAt(target, false, null);
        if (claim == null) {
            // Unclaimed land grants nothing.
            return false;
        }
        // A null denial means the player may build.
        return claim.checkPermission(player, ClaimPermission.Build, null) == null;
    }
}
