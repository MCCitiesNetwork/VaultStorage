package net.democracycraft.vault.internal.ui;

import net.democracycraft.vault.internal.security.VaultPermission;

/**
 * Actions available for a Vault when opening its virtual inventory.
 */
public enum VaultAction {
    /** View-only mode: player can inspect items but cannot modify them. */
    VIEW(VaultPermission.ACTION_VIEW),
    /** Edit mode: player can move items and persist changes back to the vault. */
    EDIT(VaultPermission.ACTION_EDIT),
    /** Copy mode: player can take items without affecting the original vault. */
    COPY(VaultPermission.ACTION_COPY);

    private final VaultPermission permission;

    VaultAction(VaultPermission permission) {
        this.permission = permission;
    }

    /**
     * Returns the required VaultPermission for this action.
     */
    public VaultPermission permission() {
        return permission;
    }
}
