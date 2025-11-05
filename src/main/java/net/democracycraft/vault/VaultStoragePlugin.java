package net.democracycraft.vault;

import net.democracycraft.vault.internal.command.VaultCommand;
import net.democracycraft.vault.internal.session.VaultSessionManager;
import net.democracycraft.vault.internal.store.VaultMemoryStore;
import org.bukkit.plugin.java.JavaPlugin;

public final class VaultStoragePlugin extends JavaPlugin {

    private static VaultStoragePlugin instance;
    private final VaultSessionManager sessionManager = new VaultSessionManager();
    private final VaultMemoryStore vaultStore = new VaultMemoryStore();

    public static VaultStoragePlugin getInstance() {
        return instance;
    }

    public VaultSessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * In-memory store of captured vaults for browsing and editing.
     */
    public VaultMemoryStore getVaultStore() {
        return vaultStore;
    }

    @Override
    public void onEnable() {
        instance = this;
        if (getCommand("vault") != null) {
            var cmd = new VaultCommand();
            getCommand("vault").setExecutor(cmd);
            getCommand("vault").setTabCompleter(cmd);
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
