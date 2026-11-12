package com.minecraftcitiesnetwork.vaultstorage;

import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.ChestShopService;
import com.minecraftcitiesnetwork.vaultstorage.scan.VaultScanService;
import com.minecraftcitiesnetwork.vaultstorage.region.WorldGuardService;
import com.minecraftcitiesnetwork.vaultstorage.command.LegacyVaultCommand;
import com.minecraftcitiesnetwork.vaultstorage.command.VaultCommand;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltServiceImp;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCaptureService;
import com.minecraftcitiesnetwork.vaultstorage.vault.VaultInventoryService;
import com.minecraftcitiesnetwork.vaultstorage.placement.VaultPlacementService;
import com.minecraftcitiesnetwork.vaultstorage.scan.VaultScanServiceImpl;
import com.minecraftcitiesnetwork.vaultstorage.region.WorldGuardServiceImp;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultActionMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultCaptureMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultListMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultScanMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultRegionListMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultPlacementMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.LoadingMenu;
import com.minecraftcitiesnetwork.vaultstorage.permission.VaultPermission;
import com.minecraftcitiesnetwork.vaultstorage.names.Names;
import com.minecraftcitiesnetwork.vaultstorage.names.PaperProfileNameSource;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleLifecycleManager;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleLoader;
import com.minecraftcitiesnetwork.vaultstorage.database.DatabaseSettings;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultDatabase;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultRepository;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultStorageSchemaMigrator;
import com.minecraftcitiesnetwork.vaultstorage.session.SessionQuitListener;
import com.minecraftcitiesnetwork.vaultstorage.placement.WorldGuardPlacementGrant;
import com.minecraftcitiesnetwork.vaultstorage.config.AutoYML;
import com.minecraftcitiesnetwork.vaultstorage.config.DataFolder;
import com.minecraftcitiesnetwork.vaultstorage.config.MailConfig;
import com.minecraftcitiesnetwork.vaultstorage.vault.OwnerNotifications;
import com.minecraftcitiesnetwork.vaultstorage.config.ConfigFiles;
import com.minecraftcitiesnetwork.vaultstorage.config.LegacyConfigImport;
import com.minecraftcitiesnetwork.vaultstorage.config.PluginSettings;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.spongepowered.configurate.ConfigurationNode;
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
    private final AtomicReference<PluginSettings> settings = new AtomicReference<>();
    private ModuleLifecycleManager<VaultStoragePlugin> modules;
    private Names names;
    private OwnerNotifications ownerNotifications;
    private VaultDatabase database;
    private VaultRepository repository;
    private ExecutorService databaseExecutor;
    private WorldGuardService worldGuardService;
    private BoltService boltService;

    // Database wiring

    public static VaultStoragePlugin getInstance() {
        return instance;
    }

    public VaultSessionManager getSessionManager() { return sessionManager; }


    /** Every read and write of vaults. Blocking: call it on {@link #databaseExecutor()}. */
    public VaultRepository repository() { return repository; }

    /** The connection pool and session factory, for a module that owns tables of its own. */
    public VaultDatabase database() { return database; }

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

    /** settings.yml. Read it through the reference each time: a reload swaps what it holds. */
    public AtomicReference<PluginSettings> settings() { return settings; }

    /** Player name and UUID lookups. */
    public Names names() { return names; }

    /** Tells a vault's owner about it, through whichever notification module is loaded. */
    public OwnerNotifications ownerNotifications() { return ownerNotifications; }

    @Override
    public void onEnable() {
        instance = this;
        // Settings first: the plugin cannot run without them, and the server is still starting.
        DatabaseSettings databaseSettings;
        try {
            databaseSettings = loadConfiguration();
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not load the configuration. VaultStorage is disabled.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Database first: nothing may register against a database that cannot store a vault.
        if (!startDatabase(databaseSettings)) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // A claim can only be left behind by a crash; nobody holds one now.
        CompletableFuture.supplyAsync(this.repository::releaseAllClaims, this.databaseExecutor)
                .thenAccept(released -> released.forEach(vault -> getLogger().warning("Vault " + vault
                        + " was still claimed from before the last shutdown and has been released."
                        + " If its block was placed just before a crash, its items may exist twice.")))
                .exceptionally(failure -> {
                    getLogger().log(Level.SEVERE, "Could not release vault claims left by the last shutdown.", failure);
                    return null;
                });

        // Integration services
        this.worldGuardService = new WorldGuardServiceImp();
        this.integrations.addPlacementGrant(new WorldGuardPlacementGrant(this.worldGuardService));
        this.boltService = new BoltServiceImp(this);



        // Domain services
        this.captureService = new VaultCaptureService();
        this.placementService = new VaultPlacementService();
        this.inventoryService = new VaultInventoryService();
        this.scanService = new VaultScanServiceImpl();

        // External lookup services
        this.names = new Names(new PaperProfileNameSource(), this.integrations);
        // Texts come from the old mail.yml until all text moves to messages.yml.
        AutoYML<MailConfig> notificationTexts = AutoYML.create(MailConfig.class, "mail", DataFolder.MAIL, MailConfig.HEADER);
        this.ownerNotifications = new OwnerNotifications(this.names, this.integrations::ownerNotifier,
                () -> notificationTexts.loadOrCreate(MailConfig::new).notificationTitle,
                () -> notificationTexts.loadOrCreate(MailConfig::new).vaultCreatedMessage,
                () -> notificationTexts.loadOrCreate(MailConfig::new).sweepVaultedMessage, getLogger());

        // Ensure menu YAMLs exist at startup
        VaultCaptureMenu.ensureConfig();
        VaultListMenu.ensureConfig();
        VaultActionMenu.ensureConfig();
        VaultScanMenu.ensureConfig();
        VaultRegionListMenu.ensureConfig();
        VaultPlacementMenu.ensureConfig();
        LoadingMenu.ensureConfig();

        getServer().getPluginManager().registerEvents(new SessionQuitListener(this.sessionManager), this);
        getServer().getPluginManager().registerEvents(this.inventoryService, this);

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
     * Reads database.yml and settings.yml, creating each from the bundled default the first
     * time. Values from the 1.x config.yml are carried over into a file as it is created.
     */
    private DatabaseSettings loadConfiguration() throws IOException {
        ConfigFiles files = new ConfigFiles(getDataFolder().toPath(), getLogger());
        ConfigurationNode old = files.legacy();
        boolean carryOver = !old.node("mysql", "host").virtual();
        if (carryOver && !files.exists("database.yml")) {
            getLogger().info("Carrying the settings in config.yml over to database.yml and settings.yml."
                    + " config.yml is no longer read and can be deleted.");
        }

        Map<String, String> database = new HashMap<>();
        Map<String, String> scan = new HashMap<>();
        if (carryOver) {
            database.put("url", LegacyConfigImport.databaseUrl(
                    old.node("mysql", "host").getString("localhost"), old.node("mysql", "port").getInt(3306),
                    old.node("mysql", "database").getString("vault_storage"),
                    old.node("mysql", "useSSL").getBoolean(false)));
            database.put("username", old.node("mysql", "user").getString("root"));
            database.put("password", old.node("mysql", "password").getString(""));
            for (String key : List.of("batch-size", "cache-ttl-seconds", "cooldown-seconds")) {
                if (!old.node("scan", key).virtual()) {
                    scan.put("scan." + key, String.valueOf(old.node("scan", key).getLong()));
                }
            }
        }

        DatabaseSettings databaseSettings = files.load("database.yml", ConfigFiles.bundled(getClass(), "database.yml"),
                text -> LegacyConfigImport.fill(text, database)).get(DatabaseSettings.class);
        PluginSettings pluginSettings = files.load("settings.yml", ConfigFiles.bundled(getClass(), "settings.yml"),
                text -> LegacyConfigImport.fill(text, scan)).get(PluginSettings.class);
        if (databaseSettings == null || pluginSettings == null) {
            throw new IOException("database.yml or settings.yml is empty");
        }
        this.settings.set(pluginSettings);
        return databaseSettings;
    }

    /**
     * Connects, migrates the schema and builds the repository. A failure is logged and reported
     * to the caller, which disables the plugin: a half-started plugin would remove blocks it
     * cannot store.
     */
    private boolean startDatabase(DatabaseSettings settings) {
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
            getLogger().log(Level.SEVERE, "Could not connect to or migrate the database at " + settings.url()
                    + ". Check database.yml. VaultStorage is disabled.", ex);
            return false;
        }
    }

    @Override
    public void onDisable() {
        if (this.modules != null) {
            this.modules.stop();
        }
        // Before the pool goes: an edit in progress is written back now or not at all.
        if (this.inventoryService != null && this.repository != null) {
            this.inventoryService.saveOpenViews();
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


    /** ChestShop integration; returns a no-op implementation when ChestShop is not installed. */
    public ChestShopService getChestShopService() {
        return this.integrations.chestShop();
    }

}
