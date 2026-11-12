package com.minecraftcitiesnetwork.vaultstorage;

import com.earth2me.essentials.Essentials;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultDAO;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.ChestShopService;
import com.minecraftcitiesnetwork.vaultstorage.scan.VaultScanService;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultService;
import com.minecraftcitiesnetwork.vaultstorage.region.WorldGuardService;
import com.minecraftcitiesnetwork.vaultstorage.command.LegacyVaultCommand;
import com.minecraftcitiesnetwork.vaultstorage.command.VaultCommand;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.minecraftcitiesnetwork.vaultstorage.database.DatabaseSchema;
import com.minecraftcitiesnetwork.vaultstorage.database.MySQLManager;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultDAOImpl;
import com.minecraftcitiesnetwork.vaultstorage.database.WorldEntity;
import com.minecraftcitiesnetwork.vaultstorage.autovault.RealtyOccupantChangeListener;
import com.minecraftcitiesnetwork.vaultstorage.autovault.AutoVaultService;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltServiceImp;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCaptureService;
import com.minecraftcitiesnetwork.vaultstorage.vault.VaultInventoryService;
import com.minecraftcitiesnetwork.vaultstorage.placement.VaultPlacementService;
import com.minecraftcitiesnetwork.vaultstorage.scan.VaultScanServiceImpl;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultServiceImpl;
import com.minecraftcitiesnetwork.vaultstorage.region.WorldGuardServiceImp;
import com.minecraftcitiesnetwork.vaultstorage.config.ConfigPaths;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
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
    private AutoVaultService autoVaultService;


    // Integration services
    private final Integrations integrations = new Integrations();
    private ModuleLifecycleManager<VaultStoragePlugin> modules;
    private Names names;
    private VaultService vaultService;
    private WorldGuardService worldGuardService;
    private BoltService boltService;
    private MailService mailService;
    private Essentials essentials;

    // Database wiring
    private MySQLManager mysql;
    private DatabaseSchema schema;
    private VaultDAO vaultDAO;

    public static VaultStoragePlugin getInstance() {
        return instance;
    }

    public VaultSessionManager getSessionManager() { return sessionManager; }

    public VaultService getVaultService() { return vaultService; }

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

        // Init DB and schema
        this.mysql = new MySQLManager(this);
        this.mysql.setupDatabase();
        this.schema = new DatabaseSchema(mysql);
        this.schema.createAll();
        this.schema.verifyIntegrity();
        // Bootstrap worlds dictionary to satisfy FK on vaults/worlds
        bootstrapWorlds();

        // DAO + Service
        this.vaultDAO = new VaultDAOImpl(schema);
        this.vaultService = new VaultServiceImpl(vaultDAO);
        // Register VaultService in Bukkit services

        // Integration services
        this.worldGuardService = new WorldGuardServiceImp();
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

        VaultCommand vaultCommand = new VaultCommand();
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("vault", "Vault storage tools", new LegacyVaultCommand(vaultCommand)));

        // Validate defined permissions vs plugin.yml
        validatePermissionsMapping();

        // Realty soft dependency: auto-vault containers when a region's owner/tenant changes.
        if (getServer().getPluginManager().getPlugin("Realty") != null
                && getConfig().getBoolean(ConfigPaths.AUTOVAULT_ENABLED.getPath(), true)) {
            this.autoVaultService = new AutoVaultService(this);
            getServer().getPluginManager().registerEvents(new RealtyOccupantChangeListener(autoVaultService), this);
            getLogger().info("Realty detected: auto-vault on region occupant change enabled.");
        }

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

    private void bootstrapWorlds() {
        try {
            for (World w : Bukkit.getWorlds()) {
                WorldEntity e = new WorldEntity();
                e.uuid = w.getUID();
                e.name = w.getName();
                schema.worlds().insertOrUpdateSync(e);
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to bootstrap worlds table: " + ex.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (this.modules != null) {
            this.modules.stop();
        }
        if (this.autoVaultService != null) this.autoVaultService.shutdown();
        if (this.mysql != null) this.mysql.disconnect();
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
