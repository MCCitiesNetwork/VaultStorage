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
 * Centralized policy to decide whether an actor can vault a target container.
 * <p>
 * Overlapping-region aware rules:
 * <ul>
 *   <li>Override: players with ACTION_PLACE_OVERRIDE may always vault (except the explicit owner-self-in-owner-region disallow is respected).</li>
 *   <li>Region owner: if the actor is owner of ANY overlapping region at the block, they may vault only when the container has a Bolt owner
 *       that is not the actor and that owner is neither member nor owner of ANY overlapping region at that block.</li>
 *   <li>Region member (non-owner): if the actor is member of ANY overlapping region at the block (and not owner), they may not vault.</li>
 *   <li>Non-involved: if the actor is neither owner nor member in ALL overlapping regions at the block, they may vault only their own Bolt-owned containers.</li>
 *   <li>Unprotected containers (no Bolt owner) may be vaulted only with override.</li>
 * </ul>
 * The method also returns detailed flags useful for logging and UI.
 */
public final class VaultCapturePolicy {

    private VaultCapturePolicy() {}

    /**
     * Immutable decision result for a single block.
     *
     * @param reason         Specific reason (ALLOWED if allowed is true)
     * @param regionStatuses Per-region debug breakdown.
     */
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
                UNPROTECTED_NO_OVERRIDE
            }
    }

    /**
     * Region-level debug information.
     *
     * @param regionId               Region identifier.
     * @param actorOwner             True if actor is an owner of this region.
     * @param actorMember            True if actor is a member (non-owner) of this region.
     * @param containerOwnerIsOwner  True if container owner (original owner) is an owner of this region.
     * @param containerOwnerIsMember True if container owner (original owner) is a member of this region.
     */
        public record RegionStatus(String regionId, boolean actorOwner, boolean actorMember, boolean containerOwnerIsOwner,
                                   boolean containerOwnerIsMember) implements Dto, Serializable {
    }

    /**
     * Evaluate vaulting permission for the given actor and container block.
     *
     * @param actor the player attempting to vault
     * @param block the target container block
     * @return a {@link Decision} including the final allowed flag and diagnostics
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
        java.util.List<RegionStatus> perRegionDebug = java.util.List.of();
        if (wgs != null) {
            var regs = wgs.getRegionsAt(block);
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
            baseAllowed = actorIsContainerOwner; // non-involved: only own container
        }
        // Unprotected requires override; do not auto-allow protected conflicts.

        // Override ONLY grants ability for unprotected containers; otherwise respect baseAllowed.
        boolean allowed = (originalOwner == null && hasOverride) || (!disallowedOwnerSelf && baseAllowed);
        Decision.Reason reason;
        if (allowed) {
            reason = Decision.Reason.ALLOWED;
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

    /**
     * Evaluate and log the decision details using the plugin logger.
     * Intended for interaction points (clicks or actions). Avoid using this from frequent UI ticks (e.g., actionbar).
     *
     * @param actor the player attempting to vault
     * @param block the target block
     * @param tag   a short tag identifying the source (e.g., "CaptureCheck", "CaptureMenuCheck", "ScanEntryCheck")
     * @return the {@link Decision}
     */
    public static Decision evaluateWithLog(Player actor, Block block, String tag) {
        Decision d = evaluate(actor, block);
        try {
            VaultStoragePlugin.getInstance().getLogger().info(String.format(
                    "[%s] actor=%s loc=%d,%d,%d ownerAny=%s memberAny=%s contOwner=%s contOwnerMemberOrOwnerAny=%s isContOwner=%s override=%s disallowedOwnerSelf=%s baseAllowed=%s finalAllowed=%s reason=%s regions=%d",
                    tag,
                    actor.getName(), block.getX(), block.getY(), block.getZ(),
                    d.actorOwnerAny, d.actorMemberAny, d.containerOwner,
                    d.containerOwnerMemberOrOwnerAny, d.actorIsContainerOwner, d.hasOverride,
                    d.disallowedOwnerSelf, d.baseAllowed, d.allowed, d.reason, d.regionStatuses.size()));
            for (RegionStatus rs : d.regionStatuses) {
                VaultStoragePlugin.getInstance().getLogger().info(String.format(
                        "[%s:Region] id=%s actorOwner=%s actorMember=%s contOwnerOwner=%s contOwnerMember=%s",
                        tag, rs.regionId, rs.actorOwner, rs.actorMember, rs.containerOwnerIsOwner, rs.containerOwnerIsMember));
            }
        } catch (Throwable ignored) {}
        return d;
    }
}
