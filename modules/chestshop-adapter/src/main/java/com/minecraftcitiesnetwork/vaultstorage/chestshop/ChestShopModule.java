package com.minecraftcitiesnetwork.vaultstorage.chestshop;

import com.minecraftcitiesnetwork.pluginInfrastructure.modules.SimplePluginModule;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.shop.ShopSigns;
import com.minecraftcitiesnetwork.vaultstorage.shop.ShopDirectory;
import java.nio.file.Path;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Recognises and removes ChestShop signs during capture when ChestShop is installed. */
public final class ChestShopModule extends SimplePluginModule<VaultStoragePlugin> {

    private @Nullable ShopSigns signs;

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
            // Every ChestShop type lives in ChestShopSigns, so this class loads without them.
            // Both are built before either slot is filled: a failure must leave nothing behind.
            ShopSigns created = new ChestShopSigns();
            ShopDirectory directory = null;
            if (plugin.getServer().getPluginManager().isPluginEnabled("Treasury")) {
                // Every Treasury type lives in TreasuryShopDirectory, for the same reason.
                directory = TreasuryShopDirectory.create(plugin.databaseExecutor(), plugin.getLogger());
            }
            plugin.integrations().addShopSigns(created);
            this.signs = created;
            plugin.integrations().shopDirectory(directory);
            plugin.getLogger().info(directory != null
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
        if (this.signs != null) {
            plugin.integrations().removeShopSigns(this.signs);
            this.signs = null;
        }
        plugin.integrations().shopDirectory(null);
        super.shutdown(plugin);
    }
}
