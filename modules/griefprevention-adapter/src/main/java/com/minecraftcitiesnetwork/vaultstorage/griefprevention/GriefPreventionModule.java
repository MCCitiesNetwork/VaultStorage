package com.minecraftcitiesnetwork.vaultstorage.griefprevention;

import com.minecraftcitiesnetwork.pluginInfrastructure.modules.SimplePluginModule;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.placement.PlacementGrant;
import java.nio.file.Path;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Lets a player place a vault on a GriefPrevention claim where they may build. */
public final class GriefPreventionModule extends SimplePluginModule<VaultStoragePlugin> {

    private @Nullable PlacementGrant grant;

    public GriefPreventionModule() {
    }

    @Override
    public void initialize(@NotNull VaultStoragePlugin plugin, @NotNull Path dataFolder) {
        super.initialize(plugin, dataFolder);
        if (!plugin.getServer().getPluginManager().isPluginEnabled("GriefPrevention")) {
            plugin.getLogger().info("GriefPrevention is not installed; griefprevention-adapter has nothing to do.");
            return;
        }
        try {
            // Every GriefPrevention type lives in the grant, so this class loads without them.
            PlacementGrant created = GriefPreventionPlacementGrant.create();
            plugin.integrations().addPlacementGrant(created);
            this.grant = created;
        } catch (LinkageError error) {
            plugin.getLogger().log(Level.SEVERE,
                    "GriefPrevention is enabled but its classes are not reachable from this module.", error);
        }
    }

    @Override
    public void shutdown(@NotNull VaultStoragePlugin plugin) {
        if (this.grant != null) {
            plugin.integrations().removePlacementGrant(this.grant);
            this.grant = null;
        }
        super.shutdown(plugin);
    }
}
