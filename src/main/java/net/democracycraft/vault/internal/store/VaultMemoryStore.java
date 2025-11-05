package net.democracycraft.vault.internal.store;

import net.democracycraft.vault.api.convertible.Vault;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for Vaults grouped by owner.
 */
public class VaultMemoryStore {

    private final Map<UUID, List<Vault>> byOwner = new ConcurrentHashMap<>();
    private final Map<UUID, Vault> byId = new ConcurrentHashMap<>();

    /**
     * Adds a vault to the store, preserving insertion order per owner.
     */
    public synchronized void add(Vault vault) {
        byId.put(vault.uniqueIdentifier(), vault);
        byOwner.computeIfAbsent(vault.ownerUniqueIdentifier(), k -> new ArrayList<>()).add(vault);
    }

    /**
     * Returns an immutable snapshot of all vaults.
     */
    public List<Vault> all() {
        return List.copyOf(byId.values());
    }

    /**
     * Returns the list of vaults for an owner, in insertion order.
     */
    public List<Vault> byOwner(UUID owner) {
        return List.copyOf(byOwner.getOrDefault(owner, List.of()));
    }

    /**
     * Returns the index (1-based) of a vault within its owner's list, or -1 if not present.
     */
    public int indexWithinOwner(UUID owner, UUID vaultId) {
        List<Vault> list = byOwner.get(owner);
        if (list == null) return -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).uniqueIdentifier().equals(vaultId)) return i + 1;
        }
        return -1;
    }

    /** Lookup by vault id. */
    public Vault get(UUID vaultId) { return byId.get(vaultId); }

    /**
     * Replaces the contents of a vault (by id) with a new instance.
     */
    public synchronized void update(Vault updated) {
        UUID id = updated.uniqueIdentifier();
        Vault prev = byId.get(id);
        if (prev == null) return;
        byId.put(id, updated);
        List<Vault> list = byOwner.get(prev.ownerUniqueIdentifier());
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).uniqueIdentifier().equals(id)) {
                    list.set(i, updated);
                    break;
                }
            }
        }
    }
}
