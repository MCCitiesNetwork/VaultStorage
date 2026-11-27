package com.minecraftcitiesnetwork.vaultstorage.placement;

import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.NotNull;

/** Whether a player may place a vault at a target. Names no Bukkit type. */
public final class PlacementRules {

    public enum Decision {
        ALLOWED,
        /** The player may not place vaults at all. */
        NO_PERMISSION,
        /** The player may place vaults, but not at this target. */
        NOT_HERE
    }

    private PlacementRules() {
    }

    /**
     * The place permission is always needed. Beyond it, the override is enough on its own;
     * without it some grant must allow the target.
     *
     * @param grantedHere whether any grant allows the target; not asked when the override holds
     */
    public static @NotNull Decision decide(boolean mayPlace, boolean hasOverride, @NotNull BooleanSupplier grantedHere) {
        if (!mayPlace) {
            return Decision.NO_PERMISSION;
        }
        return hasOverride || grantedHere.getAsBoolean() ? Decision.ALLOWED : Decision.NOT_HERE;
    }
}
