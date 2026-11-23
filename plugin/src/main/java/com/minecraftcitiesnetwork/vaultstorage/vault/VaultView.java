package com.minecraftcitiesnetwork.vaultstorage.vault;

import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Marks an open inventory as a vault on screen, and says which vault and in which mode. The one
 * vault listener recognises its inventories by this holder.
 */
public final class VaultView implements InventoryHolder {

    private final @NotNull UUID vault;
    private final @NotNull VaultAction action;
    private final @Nullable Runnable afterClose;
    // Set by the one caller, straight after it builds the inventory this holder is for.
    @SuppressWarnings("NullAway.Init")
    private Inventory inventory;

    public VaultView(@NotNull UUID vault, @NotNull VaultAction action, @Nullable Runnable afterClose) {
        this.vault = vault;
        this.action = action;
        this.afterClose = afterClose;
    }

    public @NotNull UUID vault() {
        return this.vault;
    }

    public @NotNull VaultAction action() {
        return this.action;
    }

    public @Nullable Runnable afterClose() {
        return this.afterClose;
    }

    void inventory(@NotNull Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return this.inventory;
    }
}
