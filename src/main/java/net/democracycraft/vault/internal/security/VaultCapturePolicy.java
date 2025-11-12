package net.democracycraft.vault.internal.security;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.api.service.WorldGuardService;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Centralized policy to decide whether an actor can vault a target block.
 * <p>
 * Overlapping-region aware rules (owners and members are treated equally as "participants"):
 * <ul>
 *   <li>Participant (owner or member of any overlapping region): may vault only when the block has a Bolt owner
 *       that is not the actor and that owner is not a participant of ANY overlapping region at that block.</li>
 *   <li>Non-participant (not owner nor member in all overlapping regions): may vault only their own Bolt-owned blocks.</li>
 *   <li>Unprotected blocks (no Bolt owner) are never vaultable unless override is present.</li>
 *   <li>Blocks outside any region are vaultable only with override.</li>
 * </ul>
 * The method also returns detailed flags useful for logging and UI.
 */
public final class VaultCapturePolicy {

    private VaultCapturePolicy() {}

    public record Decision(boolean allowed, boolean hasOverride, boolean actorParticipantAny,
                           boolean containerOwnerParticipantAny, boolean actorIsContainerOwner,
                           boolean disallowedOwnerSelf, boolean baseAllowed, UUID containerOwner, Reason reason,
                           List<RegionStatus> regionStatuses) implements Dto, Serializable {
        public enum Reason {
            /**
             * Allowed (no blocking condition).
             */
            ALLOWED,
            /**
             * Actor participates in an overlapping region and also is container owner (self-disallow).
             */
            OWNER_SELF_IN_REGION,
            /**
             * Container owner (original owner) is participant of at least one overlapping region, blocking participant capture.
             */
            CONTAINER_OWNER_IN_OVERLAP,
            /**
             * Actor not involved (no membership/ownership) and container is not owned by actor.
             */
            NOT_INVOLVED_NOT_OWNER,
            /**
             * Container has no Bolt owner and actor lacks override permission.
             */
            UNPROTECTED_NO_OVERRIDE,
            /**
             * The block is outside of any region.
             */
            NOT_IN_REGION
        }
    }

    public record RegionStatus(String regionId, boolean actorParticipant, boolean containerOwnerParticipates) implements Dto, Serializable {
        /*
         * Per-region participation snapshot.
         * actorParticipant: true if the actor is owner or member of this specific region.
         * containerOwnerParticipates: true if the container's Bolt owner is owner or member of this specific region.
         */
    }

    /**
     * Evaluate vaulting permission for the given actor and block.
     * Rules recap: requires Bolt owner; requires region participation unless override is present.
     */
    public static Decision evaluate(Player actor, Block block) {
        boolean hasOverride = VaultPermission.ACTION_PLACE_OVERRIDE.has(actor);
        BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
        UUID originalOwner = null;
        if (bolt != null) {
            try { originalOwner = bolt.getOwner(block); } catch (Throwable ignored) {}
        }

        boolean actorIsContainerOwner = originalOwner != null && originalOwner.equals(actor.getUniqueId());
        WorldGuardService wgs = VaultStoragePlugin.getInstance().getWorldGuardService();

        boolean actorParticipantAny = false;
        boolean containerOwnerParticipantAny = false;
        boolean inAnyRegion = false;

        List<RegionStatus> perRegionDebug = List.of();

        if (wgs != null) {
            var regions = wgs.getRegionsAt(block);
            inAnyRegion = !regions.isEmpty();
            UUID actorUuid = actor.getUniqueId();
            List<RegionStatus> debugList = new ArrayList<>();

            for (var region : regions) {
                boolean actorParticipant = region.isPartOfRegion(actorUuid);
                boolean containerOwnerParticipates = false;
                if (originalOwner != null) {
                    containerOwnerParticipates = region.isPartOfRegion(originalOwner);
                }

                BoundingBox box = region.boundingBox();

                // Determine if this region contains or is contained by another region
                boolean isParent = regions.stream().anyMatch(r -> r != region && box.contains(r.boundingBox()));
                boolean isChild  = regions.stream().anyMatch(r -> r != region && r.boundingBox().contains(box));

                // Only mark as participant if:
                //  - actor participates in this region
                //  - AND this region is NOT a parent of another overlapping one
                // (Prevents parent region membership from granting access to child regions)
                if (actorParticipant && !isParent) {
                    actorParticipantAny = true;
                }

                if (containerOwnerParticipates) {
                    containerOwnerParticipantAny = true;
                }

                debugList.add(new RegionStatus(region.id(), actorParticipant, containerOwnerParticipates));
            }

            perRegionDebug = Collections.unmodifiableList(debugList);
        }

        boolean disallowedOwnerSelf = actorParticipantAny && actorIsContainerOwner;
        boolean baseAllowed;

        if (actorParticipantAny) {
            baseAllowed = (originalOwner != null) && !actorIsContainerOwner && !containerOwnerParticipantAny;
        } else {
            baseAllowed = actorIsContainerOwner; // non-participant: only own blocks
        }

        boolean allowed = (originalOwner != null)
                && !disallowedOwnerSelf
                && ((inAnyRegion && baseAllowed) || hasOverride);

        Decision.Reason reason;
        if (allowed) {
            reason = Decision.Reason.ALLOWED;
        } else if (!inAnyRegion) {
            reason = Decision.Reason.NOT_IN_REGION;
        } else if (disallowedOwnerSelf) {
            reason = Decision.Reason.OWNER_SELF_IN_REGION;
        } else if (actorParticipantAny && containerOwnerParticipantAny) {
            reason = Decision.Reason.CONTAINER_OWNER_IN_OVERLAP;
        } else if (originalOwner == null) {
            reason = Decision.Reason.UNPROTECTED_NO_OVERRIDE;
        } else {
            reason = Decision.Reason.NOT_INVOLVED_NOT_OWNER;
        }

        return new Decision(
                allowed,
                hasOverride,
                actorParticipantAny,
                containerOwnerParticipantAny,
                actorIsContainerOwner,
                disallowedOwnerSelf,
                baseAllowed,
                originalOwner,
                reason,
                perRegionDebug
        );
    }


}
