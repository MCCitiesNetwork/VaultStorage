package com.minecraftcitiesnetwork.vaultstorage.namebook;

import com.minecraftcitiesnetwork.pluginInfrastructure.modules.SimplePluginModule;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.names.NameSource;
import java.nio.file.Path;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;

/** Makes Namebook the plugin's name lookup when Namebook is installed. */
public final class NamebookModule extends SimplePluginModule<VaultStoragePlugin> {

    public NamebookModule() {
    }

    @Override
    public void initialize(@NotNull VaultStoragePlugin plugin, @NotNull Path dataFolder) {
        super.initialize(plugin, dataFolder);
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Namebook")) {
            plugin.getLogger().info("Namebook is not installed; namebook-adapter has nothing to do.");
            return;
        }
        try {
            // Every Namebook type lives in NamebookNameSource, so this class loads without them.
            NameSource source = NamebookNameSource.find(plugin.getServer().getServicesManager());
            if (source == null) {
                plugin.getLogger().warning("Namebook is enabled but has not registered its service.");
                return;
            }
            plugin.integrations().primaryNames(source);
            plugin.getLogger().info("Player names are read from Namebook.");
        } catch (LinkageError error) {
            plugin.getLogger().log(Level.SEVERE,
                    "Namebook is enabled but its classes are not reachable from this module.", error);
        }
    }

    @Override
    public void shutdown(@NotNull VaultStoragePlugin plugin) {
        plugin.integrations().primaryNames(null);
        super.shutdown(plugin);
    }
}
