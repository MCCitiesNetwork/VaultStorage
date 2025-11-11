package net.democracycraft.vault.internal.database;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.internal.database.entity.*;
import net.democracycraft.vault.internal.database.table.AutoTable;

import java.sql.ResultSet;
import java.util.logging.Level;

/**
 * Database schema bootstrapper for Vault.
 */
public class DatabaseSchema {
    private final MySQLManager mysql;

    // Tables
    private final AutoTable<VaultEntity> vaults;
    private final AutoTable<WorldEntity> worlds;
    private final AutoTable<VaultOwnerEntity> vaultOwners;
    private final AutoTable<VaultItemEntity> vaultItems;

    public DatabaseSchema(MySQLManager mysql) {
        this.mysql = mysql;
        this.vaults = new AutoTable<>(mysql, VaultEntity.class, "vaults", "uuid");
        this.worlds = new AutoTable<>(mysql, WorldEntity.class, "worlds", "uuid");
        this.vaultOwners = new AutoTable<>(mysql, VaultOwnerEntity.class, "vault_owners", "vaultUuid"); // PK is vaultUuid (1:1)
        this.vaultItems = new AutoTable<>(mysql, VaultItemEntity.class, "vault_items", "uuid");
    }

    /** Creates all tables and adds necessary indexes/constraints. */
    public void createAll() {
        // Base tables via AutoTable create
        try {
            vaults.createTable();
            worlds.createTable();
            vaultOwners.createTable();
            vaultItems.createTable();
        } catch (Exception e) {
            // continue to add constraints even if some exist; log and proceed
            mysql.withConnection(conn -> null);
        }

        // Constraints and indexes (ignore if they already exist)
        mysql.withConnection(conn -> {
            try (var st = conn.createStatement()) {
                // If legacy unique index exists for location, drop it to allow multiple vaults at same coords
                st.execute("ALTER TABLE `vaults` DROP INDEX `uq_vault_loc`");
            } catch (Exception ignored) {}

            try (var st = conn.createStatement()) {
                st.execute("CREATE INDEX `idx_vault_loc` ON `vaults`(`worldUuid`,`x`,`y`,`z`)");
            } catch (Exception ignored) {}

            try (var st = conn.createStatement()) {
                // World dictionary unique by name (optional)
                st.execute("ALTER TABLE `worlds` ADD UNIQUE `uq_world_name` (`name`)");
            } catch (Exception ignored) {}

            try (var st = conn.createStatement()) {
                st.execute("ALTER TABLE `vaults` ADD COLUMN `material` VARCHAR(64) NULL AFTER `z`");
            } catch (Exception ignored) {}
            try (var st = conn.createStatement()) {
                st.execute("ALTER TABLE `vaults` ADD COLUMN `blockData` TEXT NULL AFTER `material`");
            } catch (Exception ignored) {}

            // Foreign keys to worlds
            try (var st = conn.createStatement()) {
                st.execute("ALTER TABLE `vaults` ADD CONSTRAINT `fk_vault_world` FOREIGN KEY (`worldUuid`) REFERENCES `worlds`(`uuid`) ON DELETE RESTRICT ON UPDATE RESTRICT");
            } catch (Exception ignored) {}

            // Foreign keys among vault tables
            try (var st = conn.createStatement()) {
                st.execute("ALTER TABLE `vault_owners` ADD CONSTRAINT `fk_vo_vault` FOREIGN KEY (`vaultUuid`) REFERENCES `vaults`(`uuid`) ON DELETE CASCADE ON UPDATE RESTRICT");
            } catch (Exception ignored) {}

            try (var st = conn.createStatement()) {
                st.execute("ALTER TABLE `vault_items` ADD CONSTRAINT `fk_vi_vault` FOREIGN KEY (`vaultUuid`) REFERENCES `vaults`(`uuid`) ON DELETE CASCADE ON UPDATE RESTRICT");
            } catch (Exception ignored) {}

            // Secondary indexes and uniques
            try (var st = conn.createStatement()) {
                st.execute("ALTER TABLE `vault_items` ADD UNIQUE `uq_vi_slot` (`vaultUuid`,`slot`)");
            } catch (Exception ignored) {}

            try (var st = conn.createStatement()) {
                st.execute("CREATE INDEX `idx_vo_owner` ON `vault_owners`(`ownerUuid`)");
            } catch (Exception ignored) {}

            return null;
        });
    }

    /**
     * Performs lightweight integrity checks on required tables/indexes and logs warnings for discrepancies.
     * This method is best-effort and does not throw; it helps diagnose misconfigurations in production.
     */
    public void verifyIntegrity() {
        try {
            mysql.withConnection(conn -> {
                try (ResultSet rs = conn.getMetaData().getTables(conn.getCatalog(), null, null, new String[]{"TABLE"})) {
                    boolean hasVaults = false;
                    boolean hasWorlds = false;
                    while (rs.next()) {
                        String name = rs.getString("TABLE_NAME");
                        if ("vaults".equalsIgnoreCase(name)) hasVaults = true;
                        if ("worlds".equalsIgnoreCase(name)) hasWorlds = true;
                    }
                    if (!hasVaults) VaultStoragePlugin.getInstance().getLogger().log(Level.WARNING, "Missing required table 'vaults'.");
                    if (!hasWorlds) VaultStoragePlugin.getInstance().getLogger().log(Level.WARNING, "Missing required table 'worlds'.");
                }

                // Check a couple of key columns exist on vaults
                try (ResultSet cols = conn.getMetaData().getColumns(conn.getCatalog(), null, "vaults", null)) {
                    boolean hasWorldUuid = false, hasX = false, hasY = false, hasZ = false;
                    while (cols.next()) {
                        String col = cols.getString("COLUMN_NAME");
                        if ("worldUuid".equalsIgnoreCase(col)) hasWorldUuid = true;
                        if ("x".equalsIgnoreCase(col)) hasX = true;
                        if ("y".equalsIgnoreCase(col)) hasY = true;
                        if ("z".equalsIgnoreCase(col)) hasZ = true;
                    }
                    if (!(hasWorldUuid && hasX && hasY && hasZ)) {
                        VaultStoragePlugin.getInstance().getLogger().log(Level.WARNING, "Table 'vaults' misses expected columns (worldUuid,x,y,z).");
                    }
                }
                return null;
            });
        } catch (Exception ignored) {
            // Only log at this stage; do not interrupt plugin enable.
        }
    }

    // Getters for tables
    public AutoTable<VaultEntity> vaults() { return vaults; }
    public AutoTable<WorldEntity> worlds() { return worlds; }
    public AutoTable<VaultOwnerEntity> vaultOwners() { return vaultOwners; }
    public AutoTable<VaultItemEntity> vaultItems() { return vaultItems; }
}
