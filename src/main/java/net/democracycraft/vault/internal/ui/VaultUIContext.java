package net.democracycraft.vault.internal.ui;

import java.util.UUID;

/**
 * Immutable UI context propagated through menus to enforce owner filtering and admin mode.
 */
public final class VaultUIContext {
    private final UUID actorUuid;
    private final UUID filterOwner; // null -> no filter (admin-only)
    private final boolean admin;

    /**
     * Creates a new UI context.
     * @param actorUuid the player opening the UI
     * @param filterOwner owner to filter by; null means no filter (admin-only)
     * @param admin whether admin capabilities are enabled
     */
    public VaultUIContext(UUID actorUuid, UUID filterOwner, boolean admin) {
        this.actorUuid = actorUuid;
        this.filterOwner = filterOwner;
        this.admin = admin;
    }

    /** Actor/player UUID who initiated the UI. */
    public UUID actorUuid() { return actorUuid; }
    /** Optional filter owner; null means no filter (only valid with admin=true). */
    public UUID filterOwner() { return filterOwner; }
    /** True if user has admin privileges in UI. */
    public boolean admin() { return admin; }

    /** Builds a self-filtered context (non-admin). */
    public static VaultUIContext self(UUID actorUuid) {
        return new VaultUIContext(actorUuid, actorUuid, false);

    }

    /** Builds an admin, no-filter context. */
    public static VaultUIContext admin(UUID actorUuid) {
        return new VaultUIContext(actorUuid, null, true);
    }

    /** Builds an admin context filtering by a specific owner UUID. */
    public static VaultUIContext adminFiltered(UUID actorUuid, UUID ownerUuid) {
        return new VaultUIContext(actorUuid, ownerUuid, true);
    }
}

