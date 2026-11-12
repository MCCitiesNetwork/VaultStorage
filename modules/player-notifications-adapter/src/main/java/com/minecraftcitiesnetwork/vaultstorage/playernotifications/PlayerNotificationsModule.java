package com.minecraftcitiesnetwork.vaultstorage.playernotifications;

import com.minecraftcitiesnetwork.pluginInfrastructure.modules.SimplePluginModule;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.vault.OwnerNotifier;
import java.nio.file.Path;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;

/** Tells vault owners about their vaults through PlayerNotifications when it is installed. */
public final class PlayerNotificationsModule extends SimplePluginModule<VaultStoragePlugin> {

    public PlayerNotificationsModule() {
    }

    @Override
    public void initialize(@NotNull VaultStoragePlugin plugin, @NotNull Path dataFolder) {
        super.initialize(plugin, dataFolder);
        if (!plugin.getServer().getPluginManager().isPluginEnabled("PlayerNotifications")) {
            plugin.getLogger().info("PlayerNotifications is not installed; player-notifications-adapter has nothing to do.");
            return;
        }
        try {
            // Every PlayerNotifications type lives in the notifier, so this class loads without them.
            OwnerNotifier notifier = PlayerNotificationsOwnerNotifier.register(
                    plugin.getServer().getServicesManager(), plugin.getLogger());
            if (notifier == null) {
                plugin.getLogger().warning("PlayerNotifications is enabled but has not registered its service.");
                return;
            }
            plugin.integrations().ownerNotifier(notifier);
        } catch (LinkageError error) {
            plugin.getLogger().log(Level.SEVERE,
                    "PlayerNotifications is enabled but its classes are not reachable from this module.", error);
        }
    }

    @Override
    public void shutdown(@NotNull VaultStoragePlugin plugin) {
        plugin.integrations().ownerNotifier(null);
        super.shutdown(plugin);
    }
}
