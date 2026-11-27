package com.minecraftcitiesnetwork.vaultstorage.menu;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** A menu reached from another. It takes the plugin from the menu it was reached from. */
public abstract class ChildMenuImp extends ParentMenuImp {

    private final @NotNull ParentMenu parent;

    protected ChildMenuImp(@NotNull Player player, @NotNull ParentMenu parent) {
        super(parent.plugin(), player);
        this.parent = parent;
    }

    public @NotNull ParentMenu parentMenu() {
        return this.parent;
    }
}
