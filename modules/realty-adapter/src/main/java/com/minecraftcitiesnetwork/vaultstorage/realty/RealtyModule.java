package com.minecraftcitiesnetwork.vaultstorage.realty;

import org.spongepowered.configurate.ConfigurationNode;
import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import java.util.Objects;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.SimplePluginModule;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Vaults a previous occupant's locked containers when a Realty region changes hands. */
public final class RealtyModule extends SimplePluginModule<VaultStoragePlugin> {

    // Main thread only: set at start and at the end of a reload, read by the sweep service.
    private @Nullable AutoVaultSettings settings;
    private final MessageContainer messages = new MessageContainer();
    private @Nullable AutoVaultService service;

    public RealtyModule() {
    }

    @Override
    public void initialize(@NotNull VaultStoragePlugin plugin, @NotNull Path dataFolder) {
        super.initialize(plugin, dataFolder);
        try {
            this.settings = readSettings(plugin);
            this.messages.load(RealtyMessages.load(dataFolder(), plugin.getLogger()));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read the realty module's config.yml and messages.yml.", ex);
        }
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Realty")) {
            plugin.getLogger().info("Realty is not installed; realty-adapter has nothing to do.");
            return;
        }
        try {
            // Every Realty type lives behind RealtyBinding, so this class loads without them.
            // Fallible work first: the table, before anything is registered.
            PendingSweepStore store = new PendingSweepStore(plugin.database());
            store.migrate(plugin.getLogger());
            AutoVaultService created = new AutoVaultService(plugin, store, this::settings, this.messages);
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

    /** What a reload replaces. Both files are parsed before either takes effect. */
    private record Reloaded(@NotNull AutoVaultSettings settings, @NotNull ConfigurationNode messages) {
    }

    /** Re-reads config.yml and messages.yml. If either does not parse, both stay as they were. */
    @Override
    public @NotNull CompletableFuture<Void> reload(@NotNull VaultStoragePlugin plugin) {
        return CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return new Reloaded(readSettings(plugin), RealtyMessages.load(dataFolder(), plugin.getLogger()));
                    } catch (IOException ex) {
                        throw new IllegalStateException(
                                "Could not read the realty module's config.yml and messages.yml.", ex);
                    }
                }, plugin.databaseExecutor())
                .thenAcceptAsync(reloaded -> {
                    this.settings = reloaded.settings();
                    this.messages.load(reloaded.messages());
                }, plugin.mainThread());
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
        return Objects.requireNonNull(this.settings, "settings are read before the service is built");
    }
}
