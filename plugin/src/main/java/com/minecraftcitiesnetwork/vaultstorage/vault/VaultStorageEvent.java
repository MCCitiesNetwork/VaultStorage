package com.minecraftcitiesnetwork.vaultstorage.vault;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Base of the plugin's events. They share one handler list; Bukkit calls a listener only for
 * the event type it asked for.
 */
public abstract class VaultStorageEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /** Bukkit looks this up by name when a listener registers; without it, registering fails. */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
