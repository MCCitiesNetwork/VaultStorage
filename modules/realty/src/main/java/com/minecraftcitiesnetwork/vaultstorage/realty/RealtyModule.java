package com.minecraftcitiesnetwork.vaultstorage.realty;

import java.util.Objects;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.SimplePluginModule;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Vaults a previous occupant's locked containers when a Realty region changes hands. */
public final class RealtyModule extends SimplePluginModule<VaultStoragePlugin> {

    private final AtomicReference<AutoVaultSettings> settings = new AtomicReference<>();
    private @Nullable AutoVaultService service;

    public RealtyModule() {
    }

    @Override
    public void initialize(@NotNull VaultStoragePlugin plugin, @NotNull Path dataFolder) {
        super.initialize(plugin, dataFolder);
        try {
            this.settings.set(readSettings(plugin));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read the auto-vault config.yml.", ex);
        }
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Realty")) {
            plugin.getLogger().info("Realty is not installed; realty has nothing to do.");
            return;
        }
        try {
            // Every Realty type lives behind RealtyBinding, so this class loads without them.
            // Fallible work first: the table, before anything is registered.
            PendingSweepStore store = new PendingSweepStore(plugin.database());
            store.migrate(plugin.getLogger());
            AutoVaultService created = new AutoVaultService(plugin, store, this::settings);
            registerListener(RealtyBinding.createListener(created));
            this.service = created;
            created.resume();
            plugin.getLogger().info(settings().enabled()
                    ? "Auto-vault on region occupant change enabled."
                    : "Auto-vault is loaded but turned off in its config.yml.");
        } catch (LinkageError error) {
            plugin.getLogger().log(Level.SEVERE,
                    "Realty is enabled but its classes are not reachable from this module.", error);
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not prepare the auto-vault table.", ex);
        }
    }

    /** Re-reads config.yml. A file that does not parse leaves the current settings in place. */
    @Override
    public @NotNull CompletableFuture<Void> reload(@NotNull VaultStoragePlugin plugin) {
        return CompletableFuture.runAsync(() -> {
            try {
                this.settings.set(readSettings(plugin));
            } catch (IOException ex) {
                throw new IllegalStateException("Could not read the auto-vault config.yml.", ex);
            }
        }, plugin.databaseExecutor());
    }

    private @NotNull AutoVaultSettings readSettings(@NotNull VaultStoragePlugin plugin) throws IOException {
        return AutoVaultSettingsFile.load(dataFolder(), plugin.getDataFolder().toPath(), plugin.getLogger());
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

    private @NotNull AutoVaultSettings settings() {
        return Objects.requireNonNull(this.settings.get(), "settings are read before the service is built");
    }
}
