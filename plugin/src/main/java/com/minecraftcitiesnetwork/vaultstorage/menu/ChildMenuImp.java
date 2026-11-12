package com.minecraftcitiesnetwork.vaultstorage.menu;

import org.jetbrains.annotations.NotNull;
import com.minecraftcitiesnetwork.vaultstorage.menu.AutoDialog;
import com.minecraftcitiesnetwork.vaultstorage.menu.ChildMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.ParentMenu;
import org.bukkit.entity.Player;

/**
 * Base implementation for child menus that participate in a parent menu flow.
 * Provides a reference to the parent menu and delegates dialog builder to it.
 */
public abstract class ChildMenuImp extends ParentMenuImp implements ChildMenu {

    private final ParentMenu parent;

    protected ChildMenuImp(@NotNull Player player, @NotNull ParentMenu parent, @NotNull String id) {
        super(parent.plugin(), player, id);
        this.parent = parent;
    }

    @Override
    public @NotNull ParentMenu parentMenu() {
        return parent;
    }

    @Override
    public AutoDialog.@NotNull Builder dialogBuilder() {
        return AutoDialog.builder(parent);
    }
}
