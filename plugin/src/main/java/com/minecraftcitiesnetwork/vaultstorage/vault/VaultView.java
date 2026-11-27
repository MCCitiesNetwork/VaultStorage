package com.minecraftcitiesnetwork.vaultstorage.vault;

import com.minecraftcitiesnetwork.vaultstorage.database.Claim;
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
    /** The hold an editing view has on the vault; null for a view that changes nothing. */
    private final @Nullable Claim claim;
    private final @NotNull VaultAction action;
    private final @Nullable Runnable afterClose;
    // Set by the one caller, straight after it builds the inventory this holder is for.
    @SuppressWarnings("NullAway.Init")
    private Inventory inventory;

    private VaultView(@NotNull UUID vault, @Nullable Claim claim, @NotNull VaultAction action,
                     @Nullable Runnable afterClose) {
        this.vault = vault;
        this.claim = claim;
        this.action = action;
        this.afterClose = afterClose;
    }

    /** A view whose contents are written back when it closes. It holds the vault meanwhile. */
    public static @NotNull VaultView editing(@NotNull Claim claim, @Nullable Runnable afterClose) {
        return new VaultView(claim.vault(), claim, VaultAction.EDIT, afterClose);
    }

    /** A view that changes nothing in the vault. */
    public static @NotNull VaultView readOnly(@NotNull UUID vault, @NotNull VaultAction action,
                                              @Nullable Runnable afterClose) {
        return new VaultView(vault, null, action, afterClose);
    }

    public @NotNull UUID vault() {
        return this.vault;
    }

    public @Nullable Claim claim() {
        return this.claim;
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
