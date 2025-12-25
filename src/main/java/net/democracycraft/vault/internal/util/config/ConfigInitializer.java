package net.democracycraft.vault.internal.util.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Initializes and validates the plugin configuration file.
 *
 * Responsibilities:
 * - Ensure required MySQL keys exist with safe defaults when missing.
 * - Persist defaults back to disk so the file is created on first run.
 */
public final class ConfigInitializer {

    private ConfigInitializer() {}

    /**
     * Ensures MySQL configuration keys exist; writes defaults if they are absent.
     *
     * Defaults:
     * - mysql.host = "localhost"
     * - mysql.port = 3306
     * - mysql.database = "vault"
     * - mysql.user = "root"
     * - mysql.password = ""
     * - mysql.useSSL = false
     *
     * @param plugin plugin instance (not null)
     */
    public static void ensureMysqlDefaults(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        FileConfiguration cfg = plugin.getConfig();

        setIfMissing(cfg, ConfigPaths.MYSQL_HOST.getPath(), "localhost");
        setIfMissing(cfg, ConfigPaths.MYSQL_PORT.getPath(), 3306);
        setIfMissing(cfg, ConfigPaths.MYSQL_DATABASE.getPath(), "vault_storage");
        setIfMissing(cfg, ConfigPaths.MYSQL_USER.getPath(), "root");
        setIfMissing(cfg, ConfigPaths.MYSQL_PASSWORD.getPath(), "");
        setIfMissing(cfg, ConfigPaths.MYSQL_USE_SSL.getPath(), false);
        setIfMissing(cfg, ConfigPaths.SCAN_BATCH_SIZE.getPath(), 20);
        setIfMissing(cfg, ConfigPaths.SCAN_CACHE_TTL_SECONDS.getPath(), 60);
        setIfMissing(cfg, ConfigPaths.SCAN_COOLDOWN_SECONDS.getPath(), 20);

        plugin.saveConfig();
    }

    private static void setIfMissing(FileConfiguration cfg, String path, Object value) {
        if (!cfg.contains(path)) {
            cfg.set(path, value);
        }
    }
}
