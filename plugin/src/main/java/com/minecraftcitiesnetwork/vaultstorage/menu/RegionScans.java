package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager;
import java.util.Map;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Starting a scan of one region from a menu: who may, how often, and what is shown meanwhile. */
final class RegionScans {

    private RegionScans() {
    }

    /** Staff may scan any region; anyone else only one they own or are a member of. */
    static boolean mayScan(@NotNull Player player, @NotNull VaultRegion region) {
        return player.hasPermission(Permissions.ADMIN) || region.isPartOfRegion(player.getUniqueId());
    }

    /**
     * Scans the region for the player and opens what was found. Refuses a region that is not
     * theirs or a scan too soon after their last; the loading menu is ended on every path.
     */
    static void open(@NotNull ParentMenu parent, @NotNull Player player, @NotNull String regionId,
                     @NotNull VaultUIContext context) {
        VaultStoragePlugin plugin = parent.plugin();
        World world = player.getWorld();
        VaultRegion region = plugin.worldGuard().getRegionById(regionId, world);
        if (region == null || !mayScan(player, region)) {
            player.sendMessage(plugin.text().of(MessageKeys.SCAN_NOT_YOUR_REGION));
            return;
        }
        if (!cooledDown(plugin, player)) {
            return;
        }
        new LoadingMenu(player, parent, Map.of("%player%", player.getName())).open();
        plugin.scanService().scan(player, regionId, context, results -> {
            if (!player.isOnline()) {
                return;
            }
            if (results == null) {
                // The scan could not run and has said why.
                player.closeDialog();
                return;
            }
            new VaultScanResultsMenu(player, parent, context, regionId, world, results, 0,
                    VaultScanResultsMenu.Filter.BLOCK).open();
        });
    }

    /** Whether the player may scan now; if so, the wait before their next scan starts. Staff never wait. */
    private static boolean cooledDown(@NotNull VaultStoragePlugin plugin, @NotNull Player player) {
        if (player.hasPermission(Permissions.ADMIN)) {
            return true;
        }
        VaultSessionManager.Session session = plugin.sessionManager().getOrCreate(player.getUniqueId());
        long now = System.currentTimeMillis();
        long wait = plugin.settings().scan().cooldownSeconds() * 1000L - (now - session.lastScanTime());
        if (wait > 0) {
            player.sendMessage(plugin.text().of(MessageKeys.SCAN_SCAN_COOLDOWN,
                    Map.of("%time%", String.valueOf(wait / 1000))));
            return false;
        }
        session.markScanned(now);
        return true;
    }
}
