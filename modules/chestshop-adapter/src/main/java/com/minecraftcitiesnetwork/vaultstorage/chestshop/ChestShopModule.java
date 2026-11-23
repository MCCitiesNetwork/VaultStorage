package com.minecraftcitiesnetwork.vaultstorage.chestshop;

import com.minecraftcitiesnetwork.vaultstorage.capture.ContainerOwnerSource;
import com.minecraftcitiesnetwork.vaultstorage.capture.ContainerAttachments;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.SimplePluginModule;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import java.nio.file.Path;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the plugin does about shops: a shop's signs go with its container, and a shop
 * container nobody locked belongs to the shop's player.
 */
public final class ChestShopModule extends SimplePluginModule<VaultStoragePlugin> {

    private @Nullable ContainerAttachments signs;
    private @Nullable ContainerOwnerSource owners;

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
            // Both are built before either is added: a failure must leave nothing behind.
            ContainerAttachments created = new ChestShopSigns();
            ContainerOwnerSource treasury = null;
            if (plugin.getServer().getPluginManager().isPluginEnabled("Treasury")) {
                // Every Treasury type lives in TreasuryShopOwners, for the same reason.
                treasury = TreasuryShopOwners.create(created, plugin.databaseExecutor(), plugin.getLogger());
            }
            plugin.integrations().addAttachments(created);
            this.signs = created;
            if (treasury != null) {
                plugin.integrations().addOwnerSource(treasury);
                this.owners = treasury;
            }
            plugin.getLogger().info(treasury != null
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
        if (this.owners != null) {
            plugin.integrations().removeOwnerSource(this.owners);
            this.owners = null;
        }
        if (this.signs != null) {
            plugin.integrations().removeAttachments(this.signs);
            this.signs = null;
        }
        super.shutdown(plugin);
    }
}
