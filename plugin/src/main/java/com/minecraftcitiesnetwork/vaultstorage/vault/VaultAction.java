package com.minecraftcitiesnetwork.vaultstorage.vault;

import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;
import org.jetbrains.annotations.NotNull;

/** What a player may do with a vault opened as an inventory. */
public enum VaultAction {
    /** Look, and take nothing. */
    VIEW(Permissions.ACTION_VIEW),
    /** Move items; the change is saved when the inventory closes. */
    EDIT(Permissions.ACTION_EDIT),
    /** Take copies; the vault keeps its items. */
    COPY(Permissions.ACTION_COPY);

    private final @NotNull String permission;

    VaultAction(@NotNull String permission) {
        this.permission = permission;
    }

    /** The node a player needs for this action. */
    public @NotNull String permission() {
        return this.permission;
    }
}
