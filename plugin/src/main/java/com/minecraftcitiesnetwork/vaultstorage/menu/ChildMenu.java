package com.minecraftcitiesnetwork.vaultstorage.menu;

import org.jetbrains.annotations.NotNull;
public interface ChildMenu extends  Menu {
    @NotNull ParentMenu parentMenu();

}
