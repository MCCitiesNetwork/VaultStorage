package com.minecraftcitiesnetwork.vaultstorage.vault;

import org.jetbrains.annotations.NotNull;

/** What each way of opening a vault lets a player do with the inventory on screen. */
public final class VaultViewRules {

    private VaultViewRules() {
    }

    /** Whether a click anywhere in the open view is cancelled. */
    public static boolean cancelsClick(@NotNull VaultAction action) {
        return action != VaultAction.EDIT;
    }

    /** Whether a click hands the player a copy of the clicked item. */
    public static boolean givesCopy(@NotNull VaultAction action, boolean clickedVault, boolean hasItem) {
        return action == VaultAction.COPY && clickedVault && hasItem;
    }

    /** Whether a drag is cancelled, given whether it touches the vault's slots. */
    public static boolean cancelsDrag(@NotNull VaultAction action, boolean touchesVault) {
        return switch (action) {
            case VIEW -> true;
            case COPY -> touchesVault;
            case EDIT -> false;
        };
    }

    /** Whether closing the view writes its contents back. */
    public static boolean savesOnClose(@NotNull VaultAction action) {
        return action == VaultAction.EDIT;
    }
}
