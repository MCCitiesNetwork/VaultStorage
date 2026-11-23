package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import org.jetbrains.annotations.NotNull;

/** A menu other menus are reached from. */
public interface ParentMenu extends Menu {

    /** The plugin this menu belongs to; a child menu takes it from its parent. */
    @NotNull VaultStoragePlugin plugin();
}
