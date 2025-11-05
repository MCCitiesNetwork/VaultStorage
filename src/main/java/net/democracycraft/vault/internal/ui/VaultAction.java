package net.democracycraft.vault.internal.ui;

/**
 * Actions available for a Vault when opening its virtual inventory.
 */
public enum VaultAction {
    /** View-only mode: player can inspect items but cannot modify them. */
    VIEW("vault.action.view"),
    /** Edit mode: player can move items and persist changes back to the vault. */
    EDIT("vault.action.edit"),
    /** Copy mode: player can take items without affecting the original vault. */
    COPY("vault.action.copy");

    private final String permission;

    VaultAction(String permission) {
        this.permission = permission;
    }

    /**
     * Returns the required permission node for this action.
     */
    public String permission() {
        return permission;
    }
}

