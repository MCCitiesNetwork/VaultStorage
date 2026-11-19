package com.minecraftcitiesnetwork.vaultstorage.chestshop.adapter;

import com.minecraftcitiesnetwork.pluginInfrastructure.modules.SimplePluginModule;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import java.nio.file.Path;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;

/** Recognises and removes ChestShop signs during capture when ChestShop is installed. */
public final class ChestShopModule extends SimplePluginModule<VaultStoragePlugin> {

    public ChestShopModule() {
    }

    @Override
    public void initialize(@NotNull VaultStoragePlugin plugin, @NotNull Path dataFolder) {
        super.initialize(plugin, dataFolder);
        if (!plugin.getServer().getPluginManager().isPluginEnabled("ChestShop")) {
            plugin.getLogger().info("ChestShop is not installed; chestshop-adapter has nothing to do.");
            return;
        }
        try {
            // Every ChestShop type lives in ChestShopServiceImp, so this class loads without them.
            plugin.integrations().chestShop(new ChestShopServiceImp());
            if (plugin.getServer().getPluginManager().isPluginEnabled("Treasury")) {
                // Every Treasury type lives in TreasuryShopDirectory, for the same reason.
                plugin.integrations().shopDirectory(
                        TreasuryShopDirectory.create(plugin.databaseExecutor(), plugin.getLogger()));
            }
            plugin.getLogger().info(plugin.integrations().shopDirectory() != null
                    ? "ChestShop signs are handled, and shop owners are read from Treasury."
                    : "ChestShop signs are handled. Treasury's shop registry is not available, so an"
                    + " unlocked shop container has no owner and cannot be vaulted.");
        } catch (LinkageError error) {
            plugin.getLogger().log(Level.SEVERE,
                    "ChestShop is enabled but its classes are not reachable from this module.", error);
        }
    }

    @Override
    public void shutdown(@NotNull VaultStoragePlugin plugin) {
        plugin.integrations().chestShop(null);
        plugin.integrations().shopDirectory(null);
        super.shutdown(plugin);
    }
}
