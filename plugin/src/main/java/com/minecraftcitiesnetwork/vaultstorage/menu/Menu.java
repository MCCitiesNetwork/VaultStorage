package com.minecraftcitiesnetwork.vaultstorage.menu;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** A screen shown to one player. */
public interface Menu {

    /** Builds the screen as it is now and shows it. */
    void open();

    @NotNull Player player();
}
