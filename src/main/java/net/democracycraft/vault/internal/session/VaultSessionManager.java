package net.democracycraft.vault.internal.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.VaultDto;
import net.democracycraft.vault.api.convertible.Vault;
import net.democracycraft.vault.internal.util.DynamicListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages per-player capture sessions and last resulting VaultDto.
 * Also exposes helpers to manage in-memory Vaults lifecycle.
 */
public class VaultSessionManager {
    public static class Session {
        private final DynamicListener dynamicListener = new DynamicListener();
        private VaultDto lastVaultDto;

        public DynamicListener getDynamicListener() { return dynamicListener; }
        public VaultDto getLastVaultDto() { return lastVaultDto; }
        public void setLastVaultDto(VaultDto dto) { this.lastVaultDto = dto; }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public Session getOrCreate(UUID playerId) {
        return sessions.computeIfAbsent(playerId, id -> new Session());
    }

    public Session get(UUID playerId) { return sessions.get(playerId); }

    public void end(UUID playerId) {
        Session s = sessions.remove(playerId);
        if (s != null) s.getDynamicListener().close();
    }

    public String toJson(VaultDto dto) { return gson.toJson(dto); }

    // Vault management helpers (delegate to global memory store)

    /** Adds a new Vault to the global in-memory store. */
    public void addVault(Vault vault) {
        VaultStoragePlugin.getInstance().getVaultStore().add(vault);
    }

    /** Returns all Vaults currently in memory. */
    public List<Vault> getAllVaults() {
        return VaultStoragePlugin.getInstance().getVaultStore().all();
    }

    /** Returns a Vault by its unique identifier or null if not found. */
    public Vault getVault(UUID vaultId) {
        return VaultStoragePlugin.getInstance().getVaultStore().get(vaultId);
    }

    /** Updates an existing Vault in the in-memory store. */
    public void updateVault(Vault updated) {
        VaultStoragePlugin.getInstance().getVaultStore().update(updated);
    }

    /** Returns Vaults owned by the given owner. */
    public List<Vault> byOwner(UUID owner) {
        return VaultStoragePlugin.getInstance().getVaultStore().byOwner(owner);
    }

    /** Returns the 1-based index of a Vault within its owner's list, or -1 if not found. */
    public int indexWithinOwner(UUID owner, UUID vaultId) {
        return VaultStoragePlugin.getInstance().getVaultStore().indexWithinOwner(owner, vaultId);
    }
}
