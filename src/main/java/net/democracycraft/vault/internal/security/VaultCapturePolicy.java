package net.democracycraft.vault.internal.security;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.api.service.WorldGuardService;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Centralized policy to decide whether an actor can vault a target block.
 * <p>
 * Overlapping-region aware rules:
 * <ul>
 *   <li>Region owner: if the actor is owner of ANY overlapping region at the block, they may vault only when the block has a Bolt owner
 *       that is not the actor and that owner is neither member nor owner of ANY overlapping region at that block.</li>
 *   <li>Region member (non-owner): if the actor is member of ANY overlapping region at the block (and not owner), they may not vault.</li>
 *   <li>Non-involved: if the actor is neither owner nor member in ALL overlapping regions at the block, they may vault only their own Bolt-owned blocks.</li>
 *   <li>Unprotected blocks (no Bolt owner) are never vaultable.</li>
 *   <li>Blocks outside of any region are vaultable only with override.</li>
 * </ul>
 * The method also returns detailed flags useful for logging and UI.
 */
public final class VaultCapturePolicy {

    private VaultCapturePolicy() {}

    public record Decision(boolean allowed, boolean hasOverride, boolean actorOwnerAny, boolean actorMemberAny,
                           boolean containerOwnerMemberOrOwnerAny, boolean actorIsContainerOwner,
                           boolean disallowedOwnerSelf, boolean baseAllowed, UUID containerOwner, Reason reason,
                           List<RegionStatus> regionStatuses) implements Dto, Serializable {
        public enum Reason {
            /**
             * Allowed (no blocking condition).
             */
            ALLOWED,
            /**
             * Actor is owner of an overlapping region and also container owner (self-disallow).
             */
            OWNER_SELF_IN_REGION,
            /**
             * Container owner (original owner) is member or owner of at least one overlapping region, blocking region-owner capture.
             */
            CONTAINER_OWNER_IN_OVERLAP,
            /**
             * Actor is member (non-owner) of an overlapping region; members cannot capture.
             */
            ACTOR_MEMBER_BLOCKED,
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

    public record RegionStatus(String regionId, boolean actorOwner, boolean actorMember, boolean containerOwnerIsOwner,
                               boolean containerOwnerIsMember) implements Dto, Serializable { }

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
        boolean actorOwnerAny = false;
        boolean actorMemberAny = false;
        boolean containerOwnerMemberOrOwnerAny = false;
        boolean inAnyRegion = false;
        List<RegionStatus> perRegionDebug = List.of();
        if (wgs != null) {
            var regs = wgs.getRegionsAt(block);
            inAnyRegion = !regs.isEmpty();
            UUID actorUuid = actor.getUniqueId();
            List<RegionStatus> list = new ArrayList<>();
            for (var vaultRegion : regs) {
                boolean aOwn = vaultRegion.isOwner(actorUuid);
                boolean aMem = vaultRegion.isMember(actorUuid);
                boolean cOwn = false;
                boolean cMem = false;
                if (originalOwner != null) {
                    cOwn = vaultRegion.isOwner(originalOwner);
                    cMem = vaultRegion.isMember(originalOwner);
                }
                if (aOwn) actorOwnerAny = true;
                if (aMem) actorMemberAny = true;
                if (cOwn || cMem) containerOwnerMemberOrOwnerAny = true;
                list.add(new RegionStatus(vaultRegion.id(), aOwn, aMem, cOwn, cMem));
            }
            perRegionDebug = Collections.unmodifiableList(list);
        }

        boolean disallowedOwnerSelf = actorOwnerAny && actorIsContainerOwner;
        boolean baseAllowed;
        if (actorOwnerAny) {
            baseAllowed = (originalOwner != null) && !actorIsContainerOwner && !containerOwnerMemberOrOwnerAny;
        } else if (actorMemberAny) {
            baseAllowed = false;
        } else {
            baseAllowed = actorIsContainerOwner; // non-involved: only own owner blocks
        }
        // Allowed when: has owner AND not owner-self disallowed AND (in region with base rules OR override bypasses region requirement)
        boolean allowed = (originalOwner != null) && !disallowedOwnerSelf && ((inAnyRegion && baseAllowed) || hasOverride);

        Decision.Reason reason;
        if (allowed) {
            reason = Decision.Reason.ALLOWED;
        } else if (!inAnyRegion) {
            reason = Decision.Reason.NOT_IN_REGION;
        } else if (disallowedOwnerSelf) {
            reason = Decision.Reason.OWNER_SELF_IN_REGION;
        } else if (actorMemberAny && !actorOwnerAny) {
            reason = Decision.Reason.ACTOR_MEMBER_BLOCKED;
        } else if (actorOwnerAny && containerOwnerMemberOrOwnerAny) {
            reason = Decision.Reason.CONTAINER_OWNER_IN_OVERLAP;
        } else if (originalOwner == null) {
            reason = Decision.Reason.UNPROTECTED_NO_OVERRIDE;
        } else {
            reason = Decision.Reason.NOT_INVOLVED_NOT_OWNER;
        }
        return new Decision(allowed, hasOverride, actorOwnerAny, actorMemberAny,
                containerOwnerMemberOrOwnerAny, actorIsContainerOwner, disallowedOwnerSelf, baseAllowed, originalOwner, reason, perRegionDebug);
    }

}
