package com.minecraftcitiesnetwork.vaultstorage.floodgate;

import com.minecraftcitiesnetwork.pluginInfrastructure.modules.SimplePluginModule;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.menu.MenuRenderer;
import com.minecraftcitiesnetwork.vaultstorage.names.NameSource;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the plugin does for Bedrock players, when Floodgate is installed: their names, the
 * Bedrock client check, and the menus drawn as Bedrock forms.
 */
public final class FloodgateModule extends SimplePluginModule<VaultStoragePlugin> {

    private @Nullable MenuRenderer forms;

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
            // Both are built before either slot is filled: a failure must leave nothing behind.
            NameSource names = FloodgateNames.create();
            Predicate<UUID> clientCheck = FloodgateNames.clientCheck();
            MenuRenderer forms = BedrockForms.create(
                    plugin.getServer().getScheduler().getMainThreadExecutor(plugin), plugin.getLogger());
            plugin.integrations().bedrockNames(names);
            plugin.integrations().bedrockClient(clientCheck);
            plugin.integrations().addMenuRenderer(forms);
            this.forms = forms;
            plugin.getLogger().info("Bedrock names and the Bedrock client check come from Floodgate,"
                    + " and Bedrock players get the menus as forms.");
        } catch (LinkageError error) {
            plugin.getLogger().log(Level.SEVERE,
                    "Floodgate is enabled but its classes are not reachable from this module.", error);
        }
    }

    @Override
    public void shutdown(@NotNull VaultStoragePlugin plugin) {
        plugin.integrations().bedrockNames(null);
        plugin.integrations().bedrockClient(null);
        if (this.forms != null) {
            plugin.integrations().removeMenuRenderer(this.forms);
            this.forms = null;
        }
        super.shutdown(plugin);
    }
}
