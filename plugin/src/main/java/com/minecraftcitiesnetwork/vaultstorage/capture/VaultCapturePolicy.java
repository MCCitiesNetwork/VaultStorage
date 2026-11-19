package com.minecraftcitiesnetwork.vaultstorage.capture;

import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.capture.HangingVaultSupport;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;

import java.util.ArrayList;
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
                           boolean disallowedOwnerSelf, boolean baseAllowed, @Nullable UUID containerOwner, @NotNull Reason reason,
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

        return evaluateBlockPolicy(actor, block, originalOwner);
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

        return evaluateBlockPolicy(actor, supportingBlock, originalOwner);
    }

    private @NotNull Decision evaluateBlockPolicy(@NotNull Player actor, @NotNull Block block, @Nullable UUID originalOwner) {
        return CaptureRules.decide(actor.getUniqueId(), originalOwner, actor.hasPermission(Permissions.BYPASS_REGION),
                regionsAt(actor, block, originalOwner));
    }

    /** The regions at the block, as the rule needs them. No regions when WorldGuard cannot be asked. */
    private @NotNull List<CaptureRules.RegionAt> regionsAt(@NotNull Player actor, @NotNull Block block,
                                                           @Nullable UUID originalOwner) {
        List<VaultRegion> regions;
        try {
            regions = plugin.worldGuard().getRegionsAt(block);
        } catch (RuntimeException ex) {
            // Counts as "outside every region", which allows nothing without the override.
            plugin.getLogger().log(Level.WARNING, "Could not read the regions at " + formatBlock(block) + ".", ex);
            return List.of();
        }
        UUID actorUuid = actor.getUniqueId();
        List<CaptureRules.RegionAt> result = new ArrayList<>(regions.size());
        for (VaultRegion region : regions) {
            boolean encloses = regions.stream()
                    .anyMatch(other -> other != region && region.boundingBox().contains(other.boundingBox()));
            result.add(new CaptureRules.RegionAt(region.id(), region.isPartOfRegion(actorUuid),
                    originalOwner != null && region.isPartOfRegion(originalOwner), encloses));
        }
        return result;
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
        List<CaptureRules.RegionAt> regions = regionsAt(actor, containerBlock, originalOwner);

        // Region membership is not enough: the plugin cannot tell whose shop it is, and a member
        // could otherwise remove the region owner's or another member's shop sign.
        boolean allowed = hasOverride;

        Decision.Reason reason = allowed
                ? Decision.Reason.ALLOWED
                : (regions.isEmpty() ? Decision.Reason.NOT_IN_REGION : Decision.Reason.NOT_INVOLVED_NOT_OWNER);

        return new Decision(
                allowed,
                hasOverride,
                regions.stream().anyMatch(region -> region.actorHasPart() && !region.encloses()),
                regions.stream().anyMatch(CaptureRules.RegionAt::ownerHasPart),
                actorIsContainerOwner,
                false,
                allowed,
                originalOwner,
                reason,
                regions.stream().map(region -> new RegionStatus(region.id(), region.actorHasPart(),
                        region.ownerHasPart())).toList()
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

}

