package com.minecraftcitiesnetwork.vaultstorage.vault;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Base of every event this plugin fires. All are fired on the main thread. */
public abstract class VaultStorageEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /** Bukkit finds the handler list through this name. */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
