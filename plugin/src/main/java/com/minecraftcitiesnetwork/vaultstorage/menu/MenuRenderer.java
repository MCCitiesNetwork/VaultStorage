package com.minecraftcitiesnetwork.vaultstorage.menu;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Draws a menu some other way than as a Paper dialog, for players whose client a dialog does
 * not suit. The host knows no such client: a module adds a renderer for the one it knows.
 */
public interface MenuRenderer {

    /**
     * Shows the menu to the player, if this renderer is for them. Main thread.
     *
     * @return false to leave the player to the dialog
     */
    boolean show(@NotNull Player player, @NotNull MenuView view);
}
