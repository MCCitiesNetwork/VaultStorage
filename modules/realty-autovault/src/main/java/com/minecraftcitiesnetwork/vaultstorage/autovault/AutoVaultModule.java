package com.minecraftcitiesnetwork.vaultstorage.autovault;

import com.minecraftcitiesnetwork.pluginInfrastructure.modules.SimplePluginModule;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.config.ConfigPaths;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Vaults a previous occupant's locked containers when a Realty region changes hands. */
public final class AutoVaultModule extends SimplePluginModule<VaultStoragePlugin> {

    private @Nullable AutoVaultService service;

    public AutoVaultModule() {
    }

    @Override
    public void initialize(@NotNull VaultStoragePlugin plugin, @NotNull Path dataFolder) {
        super.initialize(plugin, dataFolder);
        if (!plugin.getConfig().getBoolean(ConfigPaths.AUTOVAULT_ENABLED.getPath(), true)) {
            plugin.getLogger().info("auto-vault.enabled is false; realty-autovault has nothing to do.");
            return;
        }
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Realty")) {
            plugin.getLogger().info("Realty is not installed; realty-autovault has nothing to do.");
            return;
        }
        try {
            // Every Realty type lives behind RealtyBinding, so this class loads without them.
            // Fallible work first: the table, before anything is registered.
            PendingSweepStore store = new PendingSweepStore(plugin.database());
            store.migrate(plugin.getLogger());
            AutoVaultService created = new AutoVaultService(plugin, store);
            registerListener(RealtyBinding.createListener(created));
            this.service = created;
            created.resume();
            plugin.getLogger().info("Auto-vault on region occupant change enabled.");
        } catch (LinkageError error) {
            plugin.getLogger().log(Level.SEVERE,
                    "Realty is enabled but its classes are not reachable from this module.", error);
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not prepare the auto-vault table.", ex);
        }
    }

    @Override
    public void shutdown(@NotNull VaultStoragePlugin plugin) {
        if (this.service != null) {
            this.service.shutdown();
            this.service = null;
        }
        unregisterListeners();
        super.shutdown(plugin);
    }
}
