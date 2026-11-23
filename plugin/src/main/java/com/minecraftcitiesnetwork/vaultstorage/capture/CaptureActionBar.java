package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCapturePolicy;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The action bar a player sees in capture mode: who owns what they look at, and whether it can be vaulted. */
public final class CaptureActionBar {

    private static final long PERIOD_TICKS = 5L;
    private static final int REACH = 6;

    /** Simple in-memory cache for UUID->username for actionbar display. */
    private static final ConcurrentHashMap<UUID, String> NAME_CACHE = new ConcurrentHashMap<>();

    private final @NotNull VaultStoragePlugin plugin;
    private final @NotNull Player actor;

    public CaptureActionBar(@NotNull VaultStoragePlugin plugin, @NotNull Player actor) {
        this.plugin = plugin;
        this.actor = actor;
    }

    /** Shows the bar until the task is cancelled or the player leaves. */
    public @NotNull BukkitTask start() {
        return new BukkitRunnable() {
            @Override
            public void run() {
                if (!actor.isOnline()) {
                    cancel();
                    return;
                }
                actor.sendActionBar(render());
            }
        }.runTaskTimer(this.plugin, 0L, PERIOD_TICKS);
    }

    private @NotNull Component render() {
        Block target = actor.getTargetBlockExact(REACH);
        if (target == null) {
            return plugin.text().of(MessageKeys.CAPTURE_ACTION_BAR_IDLE);
        }
        if (plugin.shopSigns().isShopSign(target)) {
            Block container = ContainerBlocks.attachedContainer(target);
            if (container != null) target = container;
        }
        var boltSvc = plugin.bolt();
        UUID owner = boltSvc != null ? boltSvc.getOwner(target) : null;
        if (owner == null) {
            // Asks the shop registry in the background; the bar shows the answer once it is in,
            // and the policy below then treats an unlocked shop as its player's.
            plugin.shopOwners().ownerOf(target);
            owner = plugin.shopOwners().known(target);
        }
        String ownerName = ownerDisplayNameAsync(owner);

        VaultCapturePolicy.Decision decision = plugin.capturePolicy().evaluate(actor, target);
        String regionsList = decision.regionStatuses().stream().map(VaultCapturePolicy.RegionStatus::regionId).sorted().reduce((a,b)->a+", "+b).orElse("");
        Component reasonSegment = reasonSegment(decision, ownerName, regionsList);
        Component adminTag = decision.hasOverride()
                ? plugin.text().of(MessageKeys.CAPTURE_ACTION_BAR_ADMIN_MODE_TAG) : Component.empty();
        // The owner's name goes in as text; the two segments are components built here.
        return plugin.messages().messageFor(
                decision.allowed()
                        ? MessageKeys.CAPTURE_ACTION_BAR_VAULTABLE : MessageKeys.CAPTURE_ACTION_BAR_NOT_VAULTABLE,
                MessageContainer.value("owner", ownerName),
                MessageContainer.markup("reason-segment", reasonSegment),
                MessageContainer.markup("admin", adminTag));
    }

    /**
     * Returns a display name for the UUID without blocking the main thread.
     * Uses cache if present; otherwise returns a short UUID and schedules an async resolve.
     */
    private @NotNull String ownerDisplayNameAsync(@Nullable UUID uuid) {
        if (uuid == null) return plugin.text().plain(MessageKeys.CAPTURE_ACTION_BAR_UNPROTECTED_OWNER);
        String cached = NAME_CACHE.get(uuid);
        if (cached != null) return cached;
        String fallback = uuid.toString().substring(0, 8);
        // Async resolve and cache using CompletableFuture API
        plugin.names().nameOf(uuid).thenAccept(name ->
                name.filter(resolved -> !resolved.isBlank()).ifPresent(resolved -> NAME_CACHE.put(uuid, resolved)));
        return fallback;
    }

    /** The " | Reason: ..." part of the action bar; empty when the capture is allowed. */
    private @NotNull Component reasonSegment(VaultCapturePolicy.@NotNull Decision decision, @NotNull String ownerName,
                                           @NotNull String regionsList) {
        if (decision.allowed()) {
            return Component.empty();
        }
        String key = switch (decision.reason()) {
            case OWNER_SELF_IN_REGION -> MessageKeys.CAPTURE_REASON_OWNER_SELF_IN_REGION;
            case CONTAINER_OWNER_IN_OVERLAP -> MessageKeys.CAPTURE_REASON_CONTAINER_OWNER_IN_OVERLAP;
            case NOT_INVOLVED_NOT_OWNER -> MessageKeys.CAPTURE_REASON_NOT_INVOLVED_NOT_OWNER;
            case UNPROTECTED_NO_OVERRIDE -> MessageKeys.CAPTURE_REASON_UNPROTECTED_NO_OVERRIDE;
            case NOT_IN_REGION -> MessageKeys.CAPTURE_REASON_NOT_IN_REGION;
            case ENTITIES_REQUIRE_ADMIN -> MessageKeys.CAPTURE_REASON_HANGABLE_REQUIRES_ADMIN;
            default -> MessageKeys.CAPTURE_REASON_FALLBACK;
        };
        Component reason = plugin.text().of(key, Map.of("owner", ownerName, "regions", regionsList,
                "reason-code", decision.reason().name()));
        return plugin.messages().messageFor(
                MessageKeys.CAPTURE_ACTION_BAR_REASON_SEGMENT_TEMPLATE, MessageContainer.markup("reason", reason));
    }
}
