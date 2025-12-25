package net.democracycraft.vault.api.service;

import net.democracycraft.vault.api.data.ScanResult;
import net.democracycraft.vault.internal.ui.VaultScanMenu;
import net.democracycraft.vault.internal.ui.VaultUIContext;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/**
 * Service responsible for handling region scanning operations with caching support.
 */
public interface VaultScanService extends Service {

    /**
     * Initiates a scan for vaultable blocks within a region.
     * If a valid cached result exists, it is returned immediately via the callback.
     * Otherwise, a new asynchronous scan is started.
     *
     * @param player   The player initiating the scan.
     * @param regionId The ID of the region to scan.
     * @param context  The UI context containing filter settings.
     * @param config   The scan menu configuration.
     * @param callback The consumer to accept the list of found blocks.
     */
    void scan(Player player, String regionId, VaultUIContext context, VaultScanMenu.Config config, Consumer<List<ScanResult>> callback);

    /**
     * Invalidates the cache for a specific region or globally.
     * Useful when permissions change or blocks are modified.
     */
    void invalidateCache();
}

