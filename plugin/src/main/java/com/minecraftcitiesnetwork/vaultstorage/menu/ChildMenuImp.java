package com.minecraftcitiesnetwork.vaultstorage.menu;

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

    protected ChildMenuImp(Player player, ParentMenu parent, String id) {
        super(parent.plugin(), player, id);
        this.parent = parent;
    }

    @Override
    public ParentMenu getParentMenu() {
        return parent;
    }

    @Override
    public AutoDialog.Builder getAutoDialogBuilder() {
        return AutoDialog.builder(parent);
    }
}
