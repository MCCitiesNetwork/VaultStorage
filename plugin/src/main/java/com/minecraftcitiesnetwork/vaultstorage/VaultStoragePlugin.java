package com.minecraftcitiesnetwork.vaultstorage;

import com.earth2me.essentials.Essentials;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.ChestShopService;
import com.minecraftcitiesnetwork.vaultstorage.scan.VaultScanService;
import com.minecraftcitiesnetwork.vaultstorage.database.legacy.VaultService;
import com.minecraftcitiesnetwork.vaultstorage.region.WorldGuardService;
import com.minecraftcitiesnetwork.vaultstorage.command.LegacyVaultCommand;
import com.minecraftcitiesnetwork.vaultstorage.command.VaultCommand;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltServiceImp;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCaptureService;
import com.minecraftcitiesnetwork.vaultstorage.vault.VaultInventoryService;
import com.minecraftcitiesnetwork.vaultstorage.placement.VaultPlacementService;
import com.minecraftcitiesnetwork.vaultstorage.scan.VaultScanServiceImpl;
import com.minecraftcitiesnetwork.vaultstorage.database.legacy.VaultServiceImpl;
import com.minecraftcitiesnetwork.vaultstorage.region.WorldGuardServiceImp;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager;
import com.minecraftcitiesnetwork.vaultstorage.config.ConfigInitializer;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultActionMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultCaptureMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultListMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultScanMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultRegionListMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultPlacementMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.LoadingMenu;
import com.minecraftcitiesnetwork.vaultstorage.permission.VaultPermission;
import net.essentialsx.api.v2.services.mail.MailService;
import com.minecraftcitiesnetwork.vaultstorage.names.Names;
import com.minecraftcitiesnetwork.vaultstorage.names.PaperProfileNameSource;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleLifecycleManager;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleLoader;
import com.minecraftcitiesnetwork.vaultstorage.config.ConfigPaths;
import com.minecraftcitiesnetwork.vaultstorage.database.DatabaseSettings;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultDatabase;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultRepository;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultStorageSchemaMigrator;
import com.minecraftcitiesnetwork.vaultstorage.session.SessionQuitListener;
import com.minecraftcitiesnetwork.vaultstorage.placement.WorldGuardPlacementGrant;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.sql.SQLException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin entry point responsible for bootstrapping configuration, database, and services.
 */
public final class VaultStoragePlugin extends JavaPlugin {

    private static VaultStoragePlugin instance;
    private final VaultSessionManager sessionManager = new VaultSessionManager();

    // Domain services
    private VaultCaptureService captureService;
    private VaultPlacementService placementService;
    private VaultInventoryService inventoryService;
    private VaultScanService scanService;


    // Integration services
    private final Integrations integrations = new Integrations();
    private ModuleLifecycleManager<VaultStoragePlugin> modules;
    private Names names;
    private VaultService vaultService;
    private VaultDatabase database;
    private VaultRepository repository;
    private ExecutorService databaseExecutor;
    private WorldGuardService worldGuardService;
    private BoltService boltService;
    private MailService mailService;
    private Essentials essentials;

    // Database wiring

    public static VaultStoragePlugin getInstance() {
        return instance;
    }

    public VaultSessionManager getSessionManager() { return sessionManager; }

    public VaultService getVaultService() { return vaultService; }

    /** Every read and write of vaults. Blocking: call it on {@link #databaseExecutor()}. */
    public VaultRepository repository() { return repository; }

    /** The pool database work runs on. */
    public Executor databaseExecutor() { return databaseExecutor; }

    public WorldGuardService getWorldGuardService() { return worldGuardService; }

    public BoltService getBoltService() { return boltService; }

    /** Reusable capture domain service. */
    public VaultCaptureService getCaptureService() { return captureService; }

    /** Reusable placement domain service. */
    public VaultPlacementService getPlacementService() { return placementService; }

    /** Reusable inventory domain service. */
    public VaultInventoryService getInventoryService() { return inventoryService; }

    /** Reusable scan domain service. */
    public VaultScanService getScanService() { return scanService; }

    /** What feature modules supply; read at use time. */
    public Integrations integrations() { return integrations; }

    /** Player name and UUID lookups. */
    public Names names() { return names; }

    @Override
    public void onEnable() {
        instance = this;
        // Ensure config exists and has defaults
        ConfigInitializer.ensureMysqlDefaults(this);

        // Database first: nothing may register against a database that cannot store a vault.
        if (!startDatabase()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.vaultService = new VaultServiceImpl(this.repository);
        // A claim can only be left behind by a crash; nobody holds one now.
        CompletableFuture.supplyAsync(this.repository::releaseAllClaims, this.databaseExecutor)
                .thenAccept(released -> released.forEach(vault -> getLogger().warning("Vault " + vault
                        + " was still claimed from before the last shutdown and has been released."
                        + " If its block was placed just before a crash, its items may exist twice.")))
                .exceptionally(failure -> {
                    getLogger().log(Level.SEVERE, "Could not release vault claims left by the last shutdown.", failure);
                    return null;
                });
        // Register VaultService in Bukkit services

        // Integration services
        this.worldGuardService = new WorldGuardServiceImp();
        this.integrations.addPlacementGrant(new WorldGuardPlacementGrant(this.worldGuardService));
        this.boltService = new BoltServiceImp(this);

        RegisteredServiceProvider<MailService> mailService = getServer().getServicesManager().getRegistration(MailService.class);
        if(mailService != null) {
            this.mailService = mailService.getProvider();
        }

        var essentials = getServer().getPluginManager().getPlugin("Essentials");
        if (essentials instanceof Essentials ess) {
            this.essentials = ess;
        }


        // Domain services
        this.captureService = new VaultCaptureService();
        this.placementService = new VaultPlacementService();
        this.inventoryService = new VaultInventoryService();
        this.scanService = new VaultScanServiceImpl();

        // External lookup services
        this.names = new Names(new PaperProfileNameSource(), this.integrations);

        // Ensure menu YAMLs exist at startup
        VaultCaptureMenu.ensureConfig();
        VaultListMenu.ensureConfig();
        VaultActionMenu.ensureConfig();
        VaultScanMenu.ensureConfig();
        VaultRegionListMenu.ensureConfig();
        VaultPlacementMenu.ensureConfig();
        LoadingMenu.ensureConfig();

        getServer().getPluginManager().registerEvents(new SessionQuitListener(this.sessionManager), this);

        VaultCommand vaultCommand = new VaultCommand();
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("vault", "Vault storage tools", new LegacyVaultCommand(vaultCommand)));

        // Validate defined permissions vs plugin.yml
        validatePermissionsMapping();

        startModules();
    }

    /** Loads feature modules from the data folder. Called last, once the host's services exist. */
    private void startModules() {
        try {
            Path moduleDir = getDataFolder().toPath().resolve("modules");
            Files.createDirectories(moduleDir);
            this.modules = new ModuleLifecycleManager<>(this, new ModuleLoader(moduleDir));
            this.modules.start();
        } catch (IOException | LinkageError ex) {
            // One bad module jar must not stop the host enabling.
            getLogger().log(Level.SEVERE, "Could not load feature modules.", ex);
        }
    }

    private void validatePermissionsMapping() {
        try {
            java.util.Set<String> pluginNodes = new java.util.HashSet<>();
            for (Permission p : getPluginMeta().getPermissions()) {
                pluginNodes.add(p.getName());
            }
            java.util.Set<String> enumNodes = new java.util.HashSet<>();
            for (VaultPermission vp : VaultPermission.values()) {
                enumNodes.add(vp.node());
            }
            java.util.Set<String> missingInPluginYml = new java.util.HashSet<>(enumNodes);
            missingInPluginYml.removeAll(pluginNodes);
            java.util.Set<String> missingInEnum = new java.util.HashSet<>(pluginNodes);
            missingInEnum.removeAll(enumNodes);
            if (!missingInPluginYml.isEmpty()) {
                getLogger().warning("Permissions defined in code but missing in plugin.yml: " + missingInPluginYml);
            }
            if (!missingInEnum.isEmpty()) {
                getLogger().warning("Permissions present in plugin.yml but not in VaultPermission enum: " + missingInEnum);
            }
        } catch (Throwable t) {
            getLogger().warning("Failed to validate permissions mapping: " + t.getMessage());
        }
    }

    /**
     * Connects, migrates the schema and builds the repository. A failure is logged and reported
     * to the caller, which disables the plugin: a half-started plugin would remove blocks it
     * cannot store.
     */
    private boolean startDatabase() {
        var config = getConfig();
        DatabaseSettings settings = new DatabaseSettings(
                config.getString(ConfigPaths.MYSQL_HOST.getPath(), "localhost"),
                config.getInt(ConfigPaths.MYSQL_PORT.getPath(), 3306),
                config.getString(ConfigPaths.MYSQL_DATABASE.getPath(), "vault_storage"),
                config.getString(ConfigPaths.MYSQL_USER.getPath(), "root"),
                config.getString(ConfigPaths.MYSQL_PASSWORD.getPath(), ""),
                config.getBoolean(ConfigPaths.MYSQL_USE_SSL.getPath(), false));
        try {
            this.database = new VaultDatabase(settings);
            new VaultStorageSchemaMigrator().migrate(this.database.dataSource(), getLogger());
            this.repository = new VaultRepository(this.database);
            this.databaseExecutor = Executors.newFixedThreadPool(VaultDatabase.POOL_SIZE, task -> {
                Thread thread = new Thread(task, "vaultstorage-db");
                thread.setDaemon(true);
                return thread;
            });
            return true;
        } catch (SQLException | RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Could not connect to or migrate the database at "
                    + settings.host() + ":" + settings.port() + "/" + settings.database()
                    + ". VaultStorage is disabled.", ex);
            return false;
        }
    }

    @Override
    public void onDisable() {
        if (this.modules != null) {
            this.modules.stop();
        }
        if (this.databaseExecutor != null) {
            this.databaseExecutor.shutdownNow();
            try {
                if (!this.databaseExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    getLogger().severe("Database tasks were still running after 10 seconds.");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (this.database != null) {
            this.database.close();
        }
    }

    public MailService getMailService() {
        return mailService;
    }

    public Essentials getEssentials() {
        return essentials;
    }

    /** ChestShop integration; returns a no-op implementation when ChestShop is not installed. */
    public ChestShopService getChestShopService() {
        return this.integrations.chestShop();
    }

}
