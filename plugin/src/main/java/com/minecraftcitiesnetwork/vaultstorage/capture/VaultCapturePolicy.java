package com.minecraftcitiesnetwork.vaultstorage.capture;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.region.WorldGuardService;
import com.minecraftcitiesnetwork.vaultstorage.capture.HangingVaultSupport;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Policy evaluator for vault capture (placement) actions.
 * <p>
 * Enforces rules based on Bolt ownership and WorldGuard region participation.
 */
public final class VaultCapturePolicy {

    private final @NotNull VaultStoragePlugin plugin;

    public VaultCapturePolicy(@NotNull VaultStoragePlugin plugin) {
        this.plugin = plugin;
    }

    public record Decision(boolean allowed, boolean hasOverride, boolean actorParticipantAny,
                           boolean containerOwnerParticipantAny, boolean actorIsContainerOwner,
                           boolean disallowedOwnerSelf, boolean baseAllowed, @NotNull UUID containerOwner, @NotNull Reason reason,
                           @NotNull List<RegionStatus> regionStatuses) {
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
            NOT_IN_REGION,
            /**
             * The entity type is not supported for non-admin vaulting.
             */
            ENTITIES_REQUIRE_ADMIN
        }
    }

    public record RegionStatus(@NotNull String regionId, boolean actorParticipant, boolean containerOwnerParticipates) {
        /*
         * Per-region participation snapshot.
         * actorParticipant: true if the actor is owner or member of this specific region.
         * containerOwnerParticipates: true if the container's Bolt owner is owner or member of this specific region.
         */
    }

    /**
     * Evaluate vaulting permission for the given actor and block.
     * Rules recap: requires Bolt owner; requires region participation unless override is present.
     *
     */
    public @NotNull Decision evaluate(@NotNull Player actor, @NotNull Block block) {
        BoltService bolt = plugin.bolt();
        UUID originalOwner = null;
        if (bolt != null) {
            try {
                originalOwner = bolt.getOwner(block);
            } catch (Throwable t) {
                plugin.getLogger().warning("[VaultCapturePolicy] Error obtaining Bolt owner for block in "
                        + formatBlock(block) + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
            }
        }
        if (originalOwner == null) {
            // Nobody locked it. If it is a player's shop, it is that player's: the registry was
            // asked when the container was looked at or clicked, and the answer is used here.
            originalOwner = plugin.shopOwners().known(block);
        }

        // Log actor UUID for debugging Bedrock player issues
        UUID actorUUID = actor.getUniqueId();
        if (actorUUID.toString().matches("0{8}-0{4}-0{4}-0{4}-0{12}")) {
            plugin.getLogger().warning("[VaultCapturePolicy] Invalid actor UUID for player " + actor.getName() + ": " + actorUUID);
        }

        return evaluateBlockPolicy(actor, block, originalOwner, false);
    }

    /**
     * Same Bolt and WorldGuard rules as {@link #evaluate(Player, Block)}, but does not apply the
     * "hangings near block" restriction (used when vaulting the hanging entity itself).
     * @param actor player performing capture
     * @param hanging the hanging entity being captured
     * @param supportingBlock block the hanging is attached to
     */
    public @NotNull Decision evaluateHangingCapture(@NotNull Player actor, @NotNull Hanging hanging, @NotNull Block supportingBlock) {
        BoltService bolt = plugin.bolt();
        UUID originalOwner = null;
        if (bolt != null) {
            try {
                originalOwner = bolt.getOwner(hanging);
                if (originalOwner == null) {
                    originalOwner = bolt.getOwner(supportingBlock);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("[VaultCapturePolicy] Error obtaining Bolt owner for hanging entity in "
                        + formatBlock(supportingBlock) + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
            }
        }

        UUID actorUUID = actor.getUniqueId();
        if (actorUUID.toString().matches("0{8}-0{4}-0{4}-0{4}-0{12}")) {
            plugin.getLogger().warning("[VaultCapturePolicy] Invalid actor UUID for player " + actor.getName() + ": " + actorUUID);
        }

        return evaluateBlockPolicy(actor, supportingBlock, originalOwner, false);
    }

    private @NotNull Decision evaluateBlockPolicy(@NotNull Player actor, @NotNull Block block, @Nullable UUID originalOwner, boolean hangableRestricted) {
        boolean hasOverride = actor.hasPermission(Permissions.BYPASS_REGION);
        boolean actorIsContainerOwner = originalOwner != null && originalOwner.equals(actor.getUniqueId());

        RegionParticipation participation = computeRegionParticipation(actor, block, originalOwner);
        boolean actorParticipantAny = participation.actorParticipantAny();
        boolean containerOwnerParticipantAny = participation.containerOwnerParticipantAny();
        boolean inAnyRegion = participation.inAnyRegion();
        List<RegionStatus> perRegionDebug = participation.perRegionDebug();

        boolean disallowedOwnerSelf = actorParticipantAny && actorIsContainerOwner;
        boolean baseAllowed;

        if (actorParticipantAny) {
            // Actor is a participant of at least one relevant (non-parent) region
            // Can vault if: block has owner, actor is not the owner, and owner is not participant of any relevant region
            baseAllowed = (originalOwner != null) && !actorIsContainerOwner && !containerOwnerParticipantAny;
        } else {
            // Actor is not a participant of any relevant region
            // Can only vault their own blocks
            baseAllowed = actorIsContainerOwner;
        }

        boolean allowed = (originalOwner != null)
                && !disallowedOwnerSelf
                && !hangableRestricted
                && ((inAnyRegion && baseAllowed) || hasOverride);

        Decision.Reason reason = switch (Boolean.TRUE) {
            case Boolean b when allowed -> Decision.Reason.ALLOWED;
            case Boolean b when hangableRestricted -> Decision.Reason.ENTITIES_REQUIRE_ADMIN;
            case Boolean b when !inAnyRegion -> Decision.Reason.NOT_IN_REGION;
            case Boolean b when disallowedOwnerSelf -> Decision.Reason.OWNER_SELF_IN_REGION;
            case Boolean b when actorParticipantAny && containerOwnerParticipantAny -> Decision.Reason.CONTAINER_OWNER_IN_OVERLAP;
            case Boolean b when originalOwner == null -> Decision.Reason.UNPROTECTED_NO_OVERRIDE;
            default -> Decision.Reason.NOT_INVOLVED_NOT_OWNER;
        };

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

    private record RegionParticipation(boolean inAnyRegion, boolean actorParticipantAny,
                                       boolean containerOwnerParticipantAny, @NotNull List<RegionStatus> perRegionDebug) {}

    /** Scans WorldGuard regions at the block and computes actor/owner participation flags. */
    private @NotNull RegionParticipation computeRegionParticipation(@NotNull Player actor, @NotNull Block block, @Nullable UUID originalOwner) {
        WorldGuardService wgs = plugin.worldGuard();

        boolean actorParticipantAny = false;
        boolean containerOwnerParticipantAny = false;
        boolean inAnyRegion = false;
        List<RegionStatus> perRegionDebug = List.of();

        if (wgs != null) {
            List<VaultRegion> regions = List.of();
            try {
                regions = wgs.getRegionsAt(block);
            } catch (Throwable t) {
                plugin.getLogger().warning("[VaultCapturePolicy] Error obtaining WorldGuard regions for block in "
                        + formatBlock(block) + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
            }

            inAnyRegion = !regions.isEmpty();
            UUID actorUuid = actor.getUniqueId();
            List<RegionStatus> debugList = new ArrayList<>();

            for (var region : regions) {
                boolean actorParticipant = false;
                boolean containerOwnerParticipates = false;
                try {
                    actorParticipant = region.isPartOfRegion(actorUuid);
                    if (originalOwner != null) {
                        containerOwnerParticipates = region.isPartOfRegion(originalOwner);
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("[VaultCapturePolicy] Error evaluating participation for region "
                            + region.id() + " para bloque en " + formatBlock(block) + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
                }

                BoundingBox box;
                try {
                    box = region.boundingBox();
                } catch (Throwable t) {
                    plugin.getLogger().warning("[VaultCapturePolicy] Error obtaining boundingBox for region "
                            + region.id() + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
                    debugList.add(new RegionStatus(region.id(), actorParticipant, containerOwnerParticipates));
                    if (actorParticipant) actorParticipantAny = true;
                    if (containerOwnerParticipates) containerOwnerParticipantAny = true;
                    continue;
                }

                final BoundingBox currentBox = box;
                boolean isParent = false;
                try {
                    isParent = regions.stream().anyMatch(r -> r != region && safeContains(currentBox, r));
                } catch (Throwable t) {
                    plugin.getLogger().warning("[VaultCapturePolicy] Error evaluating parent/child for region "
                            + region.id() + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
                }

                boolean actorCountedHere = actorParticipant && !isParent;
                if (actorCountedHere) {
                    actorParticipantAny = true;
                }

                if (containerOwnerParticipates) {
                    containerOwnerParticipantAny = true;
                }

                debugList.add(new RegionStatus(region.id(), actorParticipant, containerOwnerParticipates));
            }

            perRegionDebug = Collections.unmodifiableList(debugList);
        }

        return new RegionParticipation(inAnyRegion, actorParticipantAny, containerOwnerParticipantAny, perRegionDebug);
    }

    /**
     * Evaluate whether the actor may remove a ChestShop sign on the given container.
     * Only the override permission allows it.
     */
    public @NotNull Decision evaluateChestShopSignRemoval(@NotNull Player actor, @NotNull Block containerBlock) {
        BoltService bolt = plugin.bolt();
        UUID originalOwner = null;
        if (bolt != null) {
            try {
                originalOwner = bolt.getOwner(containerBlock);
            } catch (Throwable t) {
                plugin.getLogger().warning("[VaultCapturePolicy] Error obtaining Bolt owner for block in "
                        + formatBlock(containerBlock) + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
            }
        }

        boolean hasOverride = actor.hasPermission(Permissions.BYPASS_REGION);
        boolean actorIsContainerOwner = originalOwner != null && originalOwner.equals(actor.getUniqueId());
        RegionParticipation participation = computeRegionParticipation(actor, containerBlock, originalOwner);

        // Region membership is not enough: the plugin cannot tell whose shop it is, and a member
        // could otherwise remove the region owner's or another member's shop sign.
        boolean allowed = hasOverride;

        Decision.Reason reason = allowed
                ? Decision.Reason.ALLOWED
                : (!participation.inAnyRegion() ? Decision.Reason.NOT_IN_REGION : Decision.Reason.NOT_INVOLVED_NOT_OWNER);

        return new Decision(
                allowed,
                hasOverride,
                participation.actorParticipantAny(),
                participation.containerOwnerParticipantAny(),
                actorIsContainerOwner,
                false,
                allowed,
                originalOwner,
                reason,
                participation.perRegionDebug()
        );
    }

    /**
     * Evaluate vaulting permission for a specific entity (e.g. from Scan Menu).
     */
    public @NotNull Decision evaluate(@NotNull Player actor, @NotNull Entity entity) {
        if (entity instanceof Hanging hanging) {
            Block supporting = HangingVaultSupport.resolveSupportingBlock(hanging);
            return evaluateHangingCapture(actor, hanging, supporting);
        }

        boolean hasOverride = actor.hasPermission(Permissions.BYPASS_REGION);
        boolean isAdmin = actor.hasPermission(Permissions.ADMIN);

        if (!isAdmin) {
            return new Decision(false, hasOverride, false, false, false, false, false, null, Decision.Reason.ENTITIES_REQUIRE_ADMIN, List.of());
        }

        return new Decision(true, hasOverride, false, false, false, false, true, null, Decision.Reason.ALLOWED, List.of());
    }

    private static @NotNull String formatBlock(@Nullable Block block) {
        if (block == null) {
            return "<null>";
        }
        return block.getWorld().getName() + "@" + block.getX() + "," + block.getY() + "," + block.getZ() + "[" + block.getType() + "]";
    }

    private boolean safeContains(@NotNull BoundingBox outer, @NotNull VaultRegion innerRegion) {
        try {
            BoundingBox inner = innerRegion.boundingBox();
            return outer.contains(inner);
        } catch (Throwable t) {
            plugin.getLogger().warning("[VaultCapturePolicy] Error in safeContains for region "
                    + innerRegion.id() + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
            return false;
        }
    }
}

