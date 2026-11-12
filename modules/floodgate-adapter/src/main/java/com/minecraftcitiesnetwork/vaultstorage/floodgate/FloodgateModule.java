package com.minecraftcitiesnetwork.vaultstorage.floodgate;

import com.minecraftcitiesnetwork.pluginInfrastructure.modules.SimplePluginModule;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import java.nio.file.Path;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;

/** Supplies Bedrock name lookups and the Bedrock client check when Floodgate is installed. */
public final class FloodgateModule extends SimplePluginModule<VaultStoragePlugin> {

    public FloodgateModule() {
    }

    @Override
    public void initialize(@NotNull VaultStoragePlugin plugin, @NotNull Path dataFolder) {
        super.initialize(plugin, dataFolder);
        if (!plugin.getServer().getPluginManager().isPluginEnabled("floodgate")) {
            plugin.getLogger().info("Floodgate is not installed; floodgate-adapter has nothing to do.");
            return;
        }
        try {
            // Every Floodgate type lives in FloodgateNames, so this class loads without them.
            plugin.integrations().bedrockNames(FloodgateNames.create());
            plugin.integrations().bedrockClient(FloodgateNames.clientCheck());
        } catch (LinkageError error) {
            plugin.getLogger().log(Level.SEVERE,
                    "Floodgate is enabled but its classes are not reachable from this module.", error);
        }
    }

    @Override
    public void shutdown(@NotNull VaultStoragePlugin plugin) {
        plugin.integrations().bedrockNames(null);
        plugin.integrations().bedrockClient(null);
        super.shutdown(plugin);
    }
}
