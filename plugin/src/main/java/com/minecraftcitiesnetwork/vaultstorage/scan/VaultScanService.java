package com.minecraftcitiesnetwork.vaultstorage.scan;

import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import com.minecraftcitiesnetwork.vaultstorage.Service;

import com.minecraftcitiesnetwork.vaultstorage.scan.ScanResult;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultScanMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultUIContext;
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
     * Drops a block from every cached result. Called when a block stops being a result, because
     * it was vaulted or its protection was removed, so the lists stay right without scanning the
     * region again.
     */
    void forget(@NotNull Block block);
}

