package net.democracycraft.vault;

import net.democracycraft.vault.api.dao.VaultDAO;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.api.service.VaultService;
import net.democracycraft.vault.api.service.WorldGuardService;
import net.democracycraft.vault.internal.command.VaultCommand;
import net.democracycraft.vault.internal.database.DatabaseSchema;
import net.democracycraft.vault.internal.database.MySQLManager;
import net.democracycraft.vault.internal.database.dao.VaultDAOImpl;
import net.democracycraft.vault.internal.database.entity.WorldEntity;
import net.democracycraft.vault.internal.service.BoltServiceImp;
import net.democracycraft.vault.internal.service.VaultServiceImpl;
import net.democracycraft.vault.internal.session.VaultSessionManager;
import net.democracycraft.vault.internal.util.config.ConfigInitializer;
import net.democracycraft.vault.internal.service.VaultCaptureService;
import net.democracycraft.vault.internal.service.VaultPlacementService;
import net.democracycraft.vault.internal.service.VaultInventoryService;
import net.democracycraft.vault.internal.service.WorldGuardServiceImp;
import net.democracycraft.vault.internal.ui.VaultActionMenu;
import net.democracycraft.vault.internal.ui.VaultCaptureMenu;
import net.democracycraft.vault.internal.ui.VaultListMenu;
import net.democracycraft.vault.internal.ui.VaultScanMenu;
import net.democracycraft.vault.internal.ui.VaultRegionListMenu;
import net.democracycraft.vault.internal.ui.VaultPlacementMenu;
import net.democracycraft.vault.internal.security.VaultPermission;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.ServicePriority;
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

    // Integration services
    private VaultService vaultService;
    private WorldGuardService worldGuardService;
    private BoltService boltService;

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
        getServer().getServicesManager().register(VaultService.class, this.vaultService, this, ServicePriority.Normal);

        // Integration services
        this.worldGuardService = new WorldGuardServiceImp();
        getServer().getServicesManager().register(WorldGuardService.class, this.worldGuardService, this, ServicePriority.Normal);
        this.boltService = new BoltServiceImp(this);
        getServer().getServicesManager().register(BoltService.class, this.boltService, this, ServicePriority.Normal);

        // Domain services
        this.captureService = new VaultCaptureService();
        this.placementService = new VaultPlacementService();
        this.inventoryService = new VaultInventoryService();

        // Ensure menu YAMLs exist at startup
        VaultCaptureMenu.ensureConfig();
        VaultListMenu.ensureConfig();
        VaultActionMenu.ensureConfig();
        VaultScanMenu.ensureConfig();
        VaultRegionListMenu.ensureConfig();
        VaultPlacementMenu.ensureConfig();

        if (getCommand("vault") != null) {
            var cmd = new VaultCommand();
            getCommand("vault").setExecutor(cmd);
            getCommand("vault").setTabCompleter(cmd);
        }

        // Validate defined permissions vs plugin.yml
        validatePermissionsMapping();
    }

    private void validatePermissionsMapping() {
        try {
            java.util.Set<String> pluginNodes = new java.util.HashSet<>();
            for (Permission p : getDescription().getPermissions()) {
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
        if (this.mysql != null) this.mysql.disconnect();
    }
}
