package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.shop.ShopOwnerLookup;
import com.minecraftcitiesnetwork.vaultstorage.region.WorldGuardService;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;

import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.capture.HangingVaultSupport;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Policy evaluator for vault capture (placement) actions.
 * <p>
 * Enforces rules based on Bolt ownership and WorldGuard region participation.
 */
public final class VaultCapturePolicy {

    private final @NotNull Supplier<BoltService> bolt;
    private final @NotNull Supplier<WorldGuardService> worldGuard;
    private final @NotNull Supplier<ShopOwnerLookup> shopOwners;
    private final @NotNull Logger logger;

    /** Suppliers, not services: the plugin builds them after this, and reads are at use time. */
    public VaultCapturePolicy(@NotNull Supplier<BoltService> bolt, @NotNull Supplier<WorldGuardService> worldGuard,
                              @NotNull Supplier<ShopOwnerLookup> shopOwners, @NotNull Logger logger) {
        this.bolt = bolt;
        this.worldGuard = worldGuard;
        this.shopOwners = shopOwners;
        this.logger = logger;
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
        BoltService bolt = this.bolt.get();
        UUID originalOwner = null;
        if (bolt != null) {
            try {
                originalOwner = bolt.getOwner(block);
            } catch (Throwable t) {
                this.logger.warning("[VaultCapturePolicy] Error obtaining Bolt owner for block in "
                        + formatBlock(block) + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
            }
        }
        if (originalOwner == null) {
            // Nobody locked it. If it is a player's shop, it is that player's: the registry was
            // asked when the container was looked at or clicked, and the answer is used here.
            originalOwner = this.shopOwners.get().known(block);
        }

        // Log actor UUID for debugging Bedrock player issues
        UUID actorUUID = actor.getUniqueId();
        if (actorUUID.toString().matches("0{8}-0{4}-0{4}-0{4}-0{12}")) {
            this.logger.warning("[VaultCapturePolicy] Invalid actor UUID for player " + actor.getName() + ": " + actorUUID);
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
        BoltService bolt = this.bolt.get();
        UUID originalOwner = null;
        if (bolt != null) {
            try {
                originalOwner = bolt.getOwner(hanging);
                if (originalOwner == null) {
                    originalOwner = bolt.getOwner(supportingBlock);
                }
            } catch (Throwable t) {
                this.logger.warning("[VaultCapturePolicy] Error obtaining Bolt owner for hanging entity in "
                        + formatBlock(supportingBlock) + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
            }
        }

        UUID actorUUID = actor.getUniqueId();
        if (actorUUID.toString().matches("0{8}-0{4}-0{4}-0{4}-0{12}")) {
            this.logger.warning("[VaultCapturePolicy] Invalid actor UUID for player " + actor.getName() + ": " + actorUUID);
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
            regions = this.worldGuard.get().getRegionsAt(block);
        } catch (RuntimeException ex) {
            // Counts as "outside every region", which allows nothing without the override.
            this.logger.log(Level.WARNING, "Could not read the regions at " + formatBlock(block) + ".", ex);
            return List.of();
        }
        return CaptureRegions.at(actor.getUniqueId(), originalOwner, regions);
    }

    /** Whether the actor may remove the shop signs of a container they cannot vault. */
    public boolean mayRemoveShopSign(@NotNull Player actor) {
        return CaptureRules.mayRemoveShopSign(actor.hasPermission(Permissions.BYPASS_REGION));
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

