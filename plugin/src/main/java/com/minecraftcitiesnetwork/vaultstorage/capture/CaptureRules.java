package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCapturePolicy.Decision;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCapturePolicy.RegionStatus;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Who may vault a container. The rule alone: {@link VaultCapturePolicy} gathers what it needs
 * from Bolt and WorldGuard. Names no Bukkit type.
 */
public final class CaptureRules {

    /**
     * One region at the container.
     *
     * @param actorHasPart whether the actor is an owner or member of it
     * @param ownerHasPart whether the container's owner is an owner or member of it
     * @param encloses     whether another region at the container lies inside this one. The
     *                     actor's part in an enclosing region does not count: a member of a
     *                     city is not thereby a member of each plot in it.
     */
    public record RegionAt(@NotNull String id, boolean actorHasPart, boolean ownerHasPart, boolean encloses) {
    }

    private CaptureRules() {
    }

    /**
     * Whether the actor may remove a shop sign from a container that cannot be vaulted. Only
     * the override allows it. A part in the region does not: the sign may be the region
     * owner's or another member's.
     */
    public static boolean mayRemoveShopSign(boolean hasOverride) {
        return hasOverride;
    }

    /**
     * A container is vaulted only for an owner, so one without an owner never is. Beyond that:
     * a participant of a region there may vault what belongs to someone with no part in any
     * region there, and never their own; anyone else may vault only their own. The override
     * lifts the region rules, except that a participant still cannot vault their own.
     *
     * @param owner       the container's owner, or null when it has none
     * @param hasOverride whether the actor may ignore the region rules
     * @param regions     the regions at the container
     */
    public static @NotNull Decision decide(@NotNull UUID actor, @Nullable UUID owner, boolean hasOverride,
                                           @NotNull List<RegionAt> regions) {
        boolean actorIsOwner = actor.equals(owner);
        boolean inAnyRegion = !regions.isEmpty();
        boolean actorHasPart = regions.stream().anyMatch(region -> region.actorHasPart() && !region.encloses());
        boolean ownerHasPart = regions.stream().anyMatch(RegionAt::ownerHasPart);

        boolean ownInOwnRegion = actorHasPart && actorIsOwner;
        boolean byRegionRules = actorHasPart
                ? owner != null && !actorIsOwner && !ownerHasPart
                : actorIsOwner;
        boolean allowed = owner != null && !ownInOwnRegion && ((inAnyRegion && byRegionRules) || hasOverride);

        Decision.Reason reason;
        if (allowed) {
            reason = Decision.Reason.ALLOWED;
        } else if (!inAnyRegion) {
            reason = Decision.Reason.NOT_IN_REGION;
        } else if (ownInOwnRegion) {
            reason = Decision.Reason.OWNER_SELF_IN_REGION;
        } else if (actorHasPart && ownerHasPart) {
            reason = Decision.Reason.CONTAINER_OWNER_IN_OVERLAP;
        } else if (owner == null) {
            reason = Decision.Reason.UNPROTECTED_NO_OVERRIDE;
        } else {
            reason = Decision.Reason.NOT_INVOLVED_NOT_OWNER;
        }
        List<RegionStatus> statuses = regions.stream()
                .map(region -> new RegionStatus(region.id(), region.actorHasPart(), region.ownerHasPart())).toList();
        return new Decision(allowed, hasOverride, actorHasPart, ownerHasPart, actorIsOwner, ownInOwnRegion,
                byRegionRules, owner, reason, statuses);
    }
}
