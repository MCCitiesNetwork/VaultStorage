package net.democracycraft.vault.internal.command.impl;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.api.service.WorldGuardService;
import net.democracycraft.vault.internal.command.framework.CommandContext;
import net.democracycraft.vault.internal.command.framework.Subcommand;
import net.democracycraft.vault.internal.data.VaultDtoImp;
import net.democracycraft.vault.internal.mappable.VaultImp;
import net.democracycraft.vault.internal.security.VaultPermission;
import net.democracycraft.vault.internal.service.VaultCaptureService;
import net.democracycraft.vault.internal.session.VaultSessionManager;
import net.democracycraft.vault.internal.util.item.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Subcommand: /vault capture
 * <p>
 * Authorization policy with overlapping-region awareness:
 * - Players with override permission may always capture.
 * - Region owners at the target may capture containers whose Bolt owner exists and is NOT a member nor an owner of ANY overlapping region, and is not themselves.
 * - Region members (who are not owners) cannot capture anything inside overlapping regions.
 * - Players who are non-members/non-owners across ALL overlapping regions may capture only their own Bolt-owned containers at that location.
 */
public class CaptureSubcommand implements Subcommand {
    @Override public List<String> names() { return List.of("capture", "cap"); }
    @Override public @NotNull VaultPermission permission() { return VaultPermission.ACTION_CAPTURE; }
    @Override public String usage() { return "capture"; }

    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.isPlayer()) { ctx.sender().sendMessage("Only players can use this."); return; }
        Player player = ctx.asPlayer();
        // Permission is also enforced by the dispatcher via permission(), but we give a clear message early.
        if (!VaultPermission.ACTION_CAPTURE.has(player)) {
            player.sendMessage("You don't have permission to capture containers.");
            return;
        }

        VaultSessionManager.Session session = VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(player.getUniqueId());
        session.getDynamicListener().setListener(new Listener() {
            @EventHandler
            public void onInteract(PlayerInteractEvent event) {
                if (!event.getPlayer().getUniqueId().equals(player.getUniqueId())) return;
                Action action = event.getAction();
                // Always cancel to avoid unintended interactions
                event.setCancelled(true);
                if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                    session.getDynamicListener().stop();
                    player.sendMessage("Capture cancelled.");
                    return;
                }
                if (action != Action.RIGHT_CLICK_BLOCK) return;
                Block block = event.getClickedBlock();
                if (block == null) return;
                session.getDynamicListener().stop();
                if (!(block.getState() instanceof Container)) {
                    player.sendMessage("That block is not a container.");
                    return;
                }

                boolean hasOverride = VaultPermission.ACTION_PLACE_OVERRIDE.has(player);

                // Resolve Bolt container owner (if service is present)
                BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
                UUID originalOwner = null;
                if (bolt != null) {
                    try { originalOwner = bolt.getOwner(block); } catch (Throwable ignored) {}
                }
                boolean playerIsContainerOwner = originalOwner != null && originalOwner.equals(player.getUniqueId());

                // Determine membership/ownership status across ALL overlapping regions
                boolean playerIsRegionOwnerAny = false;
                boolean playerIsRegionMemberAny = false;
                boolean containerOwnerIsRegionMemberOrOwnerAny = false; // retained for logging though no longer drives allowance
                WorldGuardService wgs2 = VaultStoragePlugin.getInstance().getWorldGuardService();
                if (wgs2 != null) {
                    BoundingBox point2 = new BoundingBox(block.getX(), block.getY(), block.getZ(), block.getX(), block.getY(), block.getZ());
                    var regs2 = wgs2.getRegionsAt(point2, block.getWorld());
                    UUID playerUuid = player.getUniqueId();
                    for (var r : regs2) {
                        if (r.isOwner(playerUuid)) playerIsRegionOwnerAny = true;
                        if (r.isMember(playerUuid)) playerIsRegionMemberAny = true;
                        if (originalOwner != null && (r.isOwner(originalOwner) || r.isMember(originalOwner))) {
                            containerOwnerIsRegionMemberOrOwnerAny = true;
                        }
                    }
                }
                boolean playerIsRegionMemberOrOwnerAny = playerIsRegionOwnerAny || playerIsRegionMemberAny;


                boolean disallowedOwnerSelf = playerIsRegionOwnerAny && playerIsContainerOwner;


                boolean baseAllowed;
                if (playerIsRegionOwnerAny) {
                    baseAllowed = (originalOwner != null) && !playerIsContainerOwner && !containerOwnerIsRegionMemberOrOwnerAny;
                } else if (playerIsRegionMemberAny) {
                    baseAllowed = false;
                } else {
                    baseAllowed = playerIsContainerOwner;
                }
                if (originalOwner == null && !hasOverride) baseAllowed = false;

                boolean allowed = !disallowedOwnerSelf && (hasOverride ? true : baseAllowed);

                try {
                    VaultStoragePlugin.getInstance().getLogger().info(String.format(
                            "[CaptureCheck] actor=%s loc=%d,%d,%d ownerAny=%s memberAny=%s contOwner=%s contOwnerMemberOrOwnerAny=%s isContOwner=%s override=%s disallowedOwnerSelf=%s baseAllowed=%s finalAllowed=%s",
                            player.getName(), block.getX(), block.getY(), block.getZ(), playerIsRegionOwnerAny, playerIsRegionMemberAny, originalOwner, containerOwnerIsRegionMemberOrOwnerAny, playerIsContainerOwner, hasOverride, disallowedOwnerSelf, baseAllowed, allowed));
                } catch (Throwable ignored) {}

                if (!allowed) {
                    player.sendMessage("Not allowed by region/container rules.");
                    return;
                }

                // Inform if no Bolt owner (final owner will be the actor via override)
                if (bolt != null && originalOwner == null && hasOverride) {
                    player.sendMessage("No Bolt owner found; you will be set as the vault owner.");
                }
                // Remove Bolt protection prior to vaulting
                if (bolt != null) {
                    try { bolt.removeProtection(block); } catch (Throwable ignored) {}
                }

                // Capture and persist
                VaultCaptureService svc = VaultStoragePlugin.getInstance().getCaptureService();
                VaultImp vault = svc.captureFromBlock(player, block);
                var plugin = VaultStoragePlugin.getInstance();
                UUID finalOwner = originalOwner != null ? originalOwner : player.getUniqueId();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    var vs = plugin.getVaultService();
                    UUID worldId = block.getWorld().getUID();
                    // Avoid duplicates at same location
                    var existing = vs.findByLocation(worldId, block.getX(), block.getY(), block.getZ());
                    if (existing != null) {
                        vs.delete(existing.uuid);
                    }
                    UUID newId;
                    {
                        var created = vs.createVault(worldId, block.getX(), block.getY(), block.getZ(), finalOwner,
                                vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                vault.blockDataString());
                        newId = created.uuid;
                    }
                    for (int i = 0; i < vault.contents().size(); i++) {
                        ItemStack it = vault.contents().get(i);
                        if (it == null) continue;
                        vs.putItem(newId, i, it.getAmount(), ItemSerialization.toBytes(it));
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        var dto = new VaultDtoImp(newId, finalOwner, List.of(),
                                vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                null, System.currentTimeMillis());
                        VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(player.getUniqueId()).setLastVaultDto(dto);
                        player.sendMessage("Vault captured.");
                    });
                });
            }
        });
        session.getDynamicListener().start();
        player.sendMessage("Right-click a container to capture it. Left-click to cancel.");
    }
}
